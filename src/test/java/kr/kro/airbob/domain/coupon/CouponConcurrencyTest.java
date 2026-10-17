package kr.kro.airbob.domain.coupon;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import kr.kro.airbob.domain.coupon.common.*;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.*;
import kr.kro.airbob.domain.coupon.repository.*;
import kr.kro.airbob.domain.coupon.service.*;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@Testcontainers
@SpringBootTest(properties = {"spring.cloud.aws.s3.enabled=false", "spring.jpa.open-in-view=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponConcurrencyTest {
	@Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");
	@Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
	@DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}
	@MockitoBean co.elastic.clients.elasticsearch.ElasticsearchClient elasticsearchClient;
	@MockitoBean org.springframework.data.elasticsearch.core.ElasticsearchOperations elasticsearchOperations;
	@MockitoBean kr.kro.airbob.search.repository.AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean io.awspring.cloud.s3.S3Template s3Template;
	@Autowired CouponSkipLockedIssueService skip;
	@Autowired CouponStockPreparationService preparation;
	@Autowired CouponService administration;
	@Autowired CouponRepository coupons;
	@Autowired MemberCouponRepository memberCoupons;
	@Autowired MemberRepository members;
	@MockitoSpyBean CouponIssueUnitStore units;
	@Autowired JdbcTemplate jdbc;
	@Autowired DataSource dataSource;
	@Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
	List<Member> users;

	@Test
	void databaseClockUsesUtcWithoutAddingSeoulOffset() {
		var before = LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1);
		var databaseNow = units.databaseNow();
		var after = LocalDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(1);
		assertThat(databaseNow).isBetween(before, after);
	}

	@BeforeEach void setUp() {
		jdbc.update("delete from coupon_issue_unit");
		memberCoupons.deleteAllInBatch();
		coupons.deleteAllInBatch();
		members.deleteAllInBatch();
		users = new ArrayList<>();
		for (int i = 0; i < 48; i++) users.add(members.save(Member.builder()
			.email("coupon-" + i + "@test.com").nickname("member-" + i).build()));
	}

	@Test
	void concurrentIssuanceNeverExceedsStock() throws Exception {
		Coupon coupon = campaign(8, true);
		AtomicInteger successes = new AtomicInteger();
		concurrent(40, index -> {
			try { issue(coupon, users.get(index).getId()); successes.incrementAndGet(); }
			catch (CouponSoldOutException | CouponIssueContendedException expected) { }
		});
		assertThat(successes.get()).isEqualTo(8);
		assertInventory(coupon, 8);
	}

	@Test
	void duplicateRequestsIssueOnceAndRollbackStock() throws Exception {
		Coupon coupon = campaign(16, true);
		concurrent(16, index -> {
			try { issue(coupon, users.getFirst().getId()); }
			catch (CouponAlreadyIssuedException | CouponIssueContendedException expected) { }
		});
		assertInventory(coupon, 1);
		assertThatThrownBy(() -> issue(coupon, users.getFirst().getId())).isInstanceOf(CouponAlreadyIssuedException.class);
		assertInventory(coupon, 1);
	}

	@Test
	void failedMemberInsertRollsBackInventory() {
		Coupon coupon = campaign(4, true);
		assertThatThrownBy(() -> issue(coupon, Long.MAX_VALUE)).isInstanceOf(DataIntegrityViolationException.class);
		assertInventory(coupon, 0);
		issue(coupon, users.getFirst().getId());
		assertInventory(coupon, 1);
	}


	@Test
	void uncommittedLastUnitIsNotSoldOut() throws Exception {
		Coupon coupon = campaign(1, true);
		try (Connection owner = dataSource.getConnection()) {
			owner.setAutoCommit(false);
			try (var remove = owner.prepareStatement("delete from coupon_issue_unit where coupon_id=?")) {
				remove.setLong(1, coupon.getId()); remove.executeUpdate();
			}
			assertThatThrownBy(() -> issue(coupon, users.getFirst().getId())).isInstanceOf(CouponIssueContendedException.class);
			owner.rollback();
		}
		assertInventory(coupon, 0);
		issue(coupon, users.getFirst().getId());
		assertThatThrownBy(() -> issue(coupon, users.get(1).getId())).isInstanceOf(CouponSoldOutException.class);
		assertInventory(coupon, 1);
	}

	@Test
	void retriesIssuanceWhenLockedStockBecomesAvailable() throws Exception {
		Coupon coupon = campaign(1, true);
		try (Connection owner = dataSource.getConnection()) {
			owner.setAutoCommit(false);
			try (var remove = owner.prepareStatement("delete from coupon_issue_unit where coupon_id=?")) {
				remove.setLong(1, coupon.getId());
				remove.executeUpdate();
			}
			AtomicInteger attempts = new AtomicInteger();
			doAnswer(call -> {
				var unit = call.callRealMethod();
				if (attempts.incrementAndGet() == 1) {
					assertThat((java.util.Optional<?>)unit).isEmpty();
					owner.rollback();
				}
				return unit;
			}).when(units).lockAvailable(coupon.getId());
			issue(coupon, users.getFirst().getId());
			assertThat(attempts.get()).isEqualTo(2);
		}
		assertInventory(coupon, 1);
	}

	@Test
	void lastUnitCommitIsVisibleInFreshSnapshot() throws Exception {
		Coupon coupon = campaign(1, true);
		try (Connection owner = dataSource.getConnection()) {
			owner.setAutoCommit(false);
			try (var remove = owner.prepareStatement("delete from coupon_issue_unit where coupon_id=?");
				var insert = owner.prepareStatement("insert into member_coupon (coupon_id,member_id,used,created_at,updated_at) values (?,?,false,utc_timestamp(6),utc_timestamp(6))")) {
				remove.setLong(1, coupon.getId()); remove.executeUpdate();
				insert.setLong(1, coupon.getId()); insert.setLong(2, users.getFirst().getId()); insert.executeUpdate();
			}
			assertThatThrownBy(() -> issue(coupon, users.get(1).getId())).isInstanceOf(CouponIssueContendedException.class);
			owner.commit();
		}
		assertThatThrownBy(() -> issue(coupon, users.get(1).getId())).isInstanceOf(CouponSoldOutException.class);
		assertInventory(coupon, 1);
	}

	@Test
	void quantityChangesRemainContiguousBeforeStart() {
		Coupon coupon = campaign(100, false);
		administration.changeQuantity(coupon.getId(), 70);
		administration.changeQuantity(coupon.getId(), 90);
		administration.changeQuantity(coupon.getId(), 90);
		assertThat(units.count(coupon.getId())).isEqualTo(90);
		assertThat(jdbc.queryForObject("select sum(unit_no) from coupon_issue_unit where coupon_id=?", Long.class, coupon.getId())).isEqualTo(90L * 91 / 2);
		open(coupon);
		assertThatThrownBy(() -> administration.changeQuantity(coupon.getId(), 95)).isInstanceOf(CouponConfigurationLockedException.class);
		assertThatThrownBy(() -> administration.changeQuantity(coupon.getId(), 50)).isInstanceOf(CouponConfigurationLockedException.class);
		assertThatThrownBy(() -> administration.deleteCoupon(coupon.getId())).isInstanceOf(CouponConfigurationLockedException.class);
	}

	@Test void simultaneousQuantityChangesAreSerialized() throws Exception {
		Coupon coupon = campaign(100, false);
		concurrent(12, index -> administration.changeQuantity(coupon.getId(), index % 2 == 0 ? 70 : 130));
		int total = coupons.findById(coupon.getId()).orElseThrow().getTotalQuantity();
		assertThat(total).isIn(70, 130);
		assertThat(units.count(coupon.getId())).isEqualTo(total);
	}

	@Test void failedDecreaseRollsBackMetadataAndDeletion() {
		Coupon coupon = campaign(10, false);
		jdbc.update("delete from coupon_issue_unit where coupon_id=? and unit_no=10", coupon.getId());
		assertThatThrownBy(() -> administration.changeQuantity(coupon.getId(), 5)).isInstanceOf(IllegalStateException.class);
		assertThat(coupons.findById(coupon.getId()).orElseThrow().getTotalQuantity()).isEqualTo(10);
		assertThat(units.count(coupon.getId())).isEqualTo(9);
	}

	@Test void preparationFailureRollsBackAndPreparedStockCannotBeReseeded() {
		Coupon coupon = unprepared(5);
		doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("injected failure"); })
			.when(units).insertRange(coupon.getId(), 1, 5);
		assertThatThrownBy(() -> preparation.prepare(coupon.getId())).hasRootCauseInstanceOf(IllegalStateException.class);
		assertThat(units.count(coupon.getId())).isZero();
		assertThat(coupons.findById(coupon.getId()).orElseThrow().isStockPrepared()).isFalse();
		doCallRealMethod().when(units).insertRange(coupon.getId(), 1, 5);
		preparation.prepare(coupon.getId());
		assertThatThrownBy(() -> preparation.prepare(coupon.getId())).isInstanceOf(CouponAlreadyPreparedException.class);
	}

	@Test void unpreparedQuantityChangesAndDiscard() {
		Coupon coupon = unprepared(10);
		administration.changeQuantity(coupon.getId(), 4);
		assertThat(units.count(coupon.getId())).isZero();
		preparation.prepare(coupon.getId());
		assertThat(units.count(coupon.getId())).isEqualTo(4);
		administration.deleteCoupon(coupon.getId());
		assertThat(coupons.findById(coupon.getId())).isEmpty();
		assertThat(units.count(coupon.getId())).isZero();
	}

	@Test void startedPreparationAndInvalidQuantitiesAreRejected() {
		Coupon coupon = unprepared(10);
		for (Integer quantity : new Integer[] {null, 0, -1})
			assertThatThrownBy(() -> administration.changeQuantity(coupon.getId(), quantity)).isInstanceOf(CouponInvalidConfigurationException.class);
		open(coupon);
		assertThatThrownBy(() -> preparation.prepare(coupon.getId())).isInstanceOf(CouponStockPreparationNotAllowedException.class);
		assertThatThrownBy(() -> issue(coupon, users.getFirst().getId())).isInstanceOf(CouponStockNotPreparedException.class);
	}

	@Test
	void issuanceUsesRepeatableReadAndConservesUnits() {
		Coupon coupon = campaign(3, true);
		List<String> observed = new ArrayList<>();
		doAnswer(call -> { observed.add(jdbc.queryForObject("select @@transaction_isolation", String.class)); return call.callRealMethod(); })
			.when(units).lockAvailable(coupon.getId());
		skip.issue(coupon.getId(), users.getFirst().getId());
		assertThat(observed).containsExactly("REPEATABLE-READ");
		assertInventory(coupon, 1);
	}

	private Coupon unprepared(int stock) {
		LocalDateTime now = units.databaseNow();
		return coupons.saveAndFlush(Coupon.builder().name("coupon").discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(1000).totalQuantity(stock).isActive(true)
			.issueStartAt(now.plusMinutes(10)).issueEndAt(now.plusHours(1))
			.usableFrom(now).usableUntil(now.plusDays(1)).build());
	}

	@Test
	void issuanceRechecksPolicyEvenWhenOpenInViewHasAlreadyCachedCoupon() {
		Coupon coupon = campaign(2, true);
		var requestEntityManager = entityManagerFactory.createEntityManager();
		org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(entityManagerFactory,
			new org.springframework.orm.jpa.EntityManagerHolder(requestEntityManager));
		try {
			assertThat(requestEntityManager.find(Coupon.class, coupon.getId()).getIsActive()).isTrue();
			jdbc.update("update coupon set is_active=false where id=?", coupon.getId());
			assertThatThrownBy(() -> issue(coupon, users.getFirst().getId())).isInstanceOf(CouponNotIssuableException.class);
			assertInventory(coupon, 0);
		} finally {
			org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(entityManagerFactory);
			requestEntityManager.close();
		}
	}
	private Coupon campaign(int stock, boolean started) {
		Coupon coupon = unprepared(stock);
		preparation.prepare(coupon.getId());
		if (started) open(coupon);
		return coupon;
	}
	private void open(Coupon coupon) {
		// Test-only clock boundary; production fixtures finish preparation before scheduling opening.
		jdbc.update("update coupon set issue_start_at=? where id=?", units.databaseNow().minusSeconds(1), coupon.getId());
	}
	private void issue(Coupon coupon, long member) {
		skip.issue(coupon.getId(), member);
	}
	private void assertInventory(Coupon coupon, int issued) {
		assertThat(memberCoupons.countByCouponId(coupon.getId())).isEqualTo(issued);
		Coupon current = coupons.findById(coupon.getId()).orElseThrow();
		assertThat(units.count(coupon.getId()) + issued).isEqualTo(current.getTotalQuantity().longValue());
	}
	private void concurrent(int count, java.util.function.IntConsumer action) throws Exception {
		try (var executor = Executors.newFixedThreadPool(count)) {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				futures.add(executor.submit(() -> {
					try { start.await(); action.accept(index); }
					catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
				}));
			}
			start.countDown();
			for (var future : futures) future.get(30, TimeUnit.SECONDS);
		}
	}
}
