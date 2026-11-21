package kr.kro.airbob.domain.wishlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.cursor.util.CursorEncoder;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistRequest;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccommodationAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccommodationDuplicateException;
import kr.kro.airbob.domain.wishlist.exception.WishlistNotFoundException;
import kr.kro.airbob.domain.wishlist.service.WishlistService;

@DataJpaTest(showSql = false)
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, WishlistService.class,
	CursorPageInfoCreator.class, CursorEncoder.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WishlistMutationIntegrationTest {

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_wishlist_mutation");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired WishlistService service;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactionManager;
	private long wishlistId;

	@BeforeEach
	void fixture() {
		for (String table : List.of("wishlist_accommodation", "wishlist", "accommodation", "member")) {
			jdbc.update("DELETE FROM " + table);
		}
		jdbc.update("""
			INSERT INTO member (id, nickname, status, created_at, updated_at)
			VALUES (10, '여행자', 'ACTIVE', NOW(6), NOW(6)), (20, '다른 여행자', 'ACTIVE', NOW(6), NOW(6))
			""");
		for (int id = 101; id <= 110; id++) {
			jdbc.update("""
				INSERT INTO accommodation (id, member_id, name, accommodation_uid, status, base_price,
					check_in_time, check_out_time, created_at, updated_at)
				VALUES (?, 20, '테스트 숙소', UUID_TO_BIN(UUID()), 'PUBLISHED', 100000, '15:00:00', '11:00:00', NOW(6), NOW(6))
				""", id);
		}
		wishlistId = service.createWishlist(new WishlistRequest.Create("여름 여행"), 10L).id();
	}

	@Test
	void lifecyclePreservesIdsLengthsAndDeleteSemantics() {
		long membershipId = add(101);
		assertThatThrownBy(() -> add(101)).isInstanceOf(WishlistAccommodationDuplicateException.class);
		assertThat(service.createWishlist(new WishlistRequest.Create("여름 여행"), 10L).id()).isNotEqualTo(wishlistId);
		assertThat(service.updateWishlist(wishlistId, new WishlistRequest.Update("가".repeat(255)), 10L).id()).isEqualTo(wishlistId);
		assertThat(service.updateWishlistAccommodation(membershipId,
			new WishlistAccommodationRequest.Update("나".repeat(1024)), 10L).id()).isEqualTo(membershipId);
		assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(name) FROM wishlist WHERE id = ?", Integer.class, wishlistId)).isEqualTo(255);
		assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(memo) FROM wishlist_accommodation WHERE id = ?", Integer.class, membershipId)).isEqualTo(1024);
		service.deleteWishlistAccommodation(membershipId, 10L);
		assertThatThrownBy(() -> service.deleteWishlistAccommodation(membershipId, 10L))
			.isInstanceOf(WishlistAccommodationAccessDeniedException.class);
		assertAggregate();
		add(102);
		service.deleteWishlist(wishlistId, 10L);
		assertThat(jdbc.queryForObject("SELECT status FROM wishlist WHERE id = ?", String.class, wishlistId)).isEqualTo("DELETED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wishlist_accommodation", Long.class)).isZero();
		assertThatThrownBy(() -> service.deleteWishlist(wishlistId, 10L)).isInstanceOf(WishlistNotFoundException.class);
	}

	@Test
	void rejectsWrongOwnersMissingResourcesAndUnpublishedAccommodation() {
		long membershipId = add(101);
		assertThatThrownBy(() -> service.updateWishlist(wishlistId, new WishlistRequest.Update("남의 목록"), 20L))
			.isInstanceOf(WishlistAccessDeniedException.class);
		assertThatThrownBy(() -> service.createWishlistAccommodation(wishlistId, new WishlistAccommodationRequest.Create(102L), 20L))
			.isInstanceOf(WishlistAccessDeniedException.class);
		assertThatThrownBy(() -> service.updateWishlistAccommodation(membershipId, new WishlistAccommodationRequest.Update("남의 메모"), 20L))
			.isInstanceOf(WishlistAccommodationAccessDeniedException.class);
		assertThatThrownBy(() -> service.deleteWishlistAccommodation(membershipId, 20L))
			.isInstanceOf(WishlistAccommodationAccessDeniedException.class);
		assertThatThrownBy(() -> service.updateWishlist(Long.MAX_VALUE, new WishlistRequest.Update("없는 목록"), 10L))
			.isInstanceOf(WishlistNotFoundException.class);
		jdbc.update("UPDATE accommodation SET status = 'UNPUBLISHED' WHERE id = 102");
		assertThatThrownBy(() -> add(102)).isInstanceOf(AccommodationNotFoundException.class);
		assertThatThrownBy(() -> add(Long.MAX_VALUE)).isInstanceOf(AccommodationNotFoundException.class);
		jdbc.update("UPDATE member SET status = 'DORMANT' WHERE id = 10");
		assertThatThrownBy(() -> service.createWishlist(new WishlistRequest.Create("새 목록"), 10L))
			.isInstanceOf(MemberNotFoundException.class);
		assertAggregate();
	}

	@Test
	void concurrentDuplicateAddsHaveOneSuccessAndOnlyDomainConflicts() throws Exception {
		var outcomes = concurrently(Collections.nCopies(8, () -> { add(101); return "added"; }));
		assertThat(outcomes).containsOnly("added", "W004");
		assertThat(outcomes.stream().filter("added"::equals)).hasSize(1);
		assertAggregate();
	}

	@Test
	void concurrentDeletesHaveOneSuccessAndNoServerError() throws Exception {
		long membershipId = add(101);
		add(102);
		var outcomes = concurrently(Collections.nCopies(6, () -> {
			service.deleteWishlistAccommodation(membershipId, 10L); return "deleted";
		}));
		assertThat(outcomes).containsOnly("deleted", "W005");
		assertThat(outcomes.stream().filter("deleted"::equals)).hasSize(1);
		assertAggregate();
	}

	@Test
	void concurrentRenamesAndAddsDoNotOverwriteTheStoredCountOrRepresentative() throws Exception {
		List<Callable<String>> actions = new ArrayList<>();
		for (long id = 101; id <= 108; id++) {
			long accommodationId = id;
			actions.add(() -> { add(accommodationId); return "added"; });
			actions.add(() -> {
				service.updateWishlist(wishlistId, new WishlistRequest.Update("여행 " + accommodationId), 10L);
				return "renamed";
			});
		}
		assertThat(concurrently(actions)).containsOnly("added", "renamed");
		assertAggregate();
	}

	@Test
	void deletingTheWholeListRacingWithAddsLeavesNoOrphanMemberships() throws Exception {
		add(101);
		List<Callable<String>> actions = new ArrayList<>();
		for (long id = 102; id <= 108; id++) {
			long accommodationId = id;
			actions.add(() -> { add(accommodationId); return "added"; });
		}
		actions.add(() -> { service.deleteWishlist(wishlistId, 10L); return "deleted"; });
		assertThat(concurrently(actions)).contains("deleted")
			.allMatch(outcome -> List.of("added", "deleted", "W001").contains(outcome));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wishlist_accommodation", Long.class)).isZero();
	}

	@Test
	void representativeReadAfterAnEarlierSnapshotDoesNotRestoreARemovedItem() throws Exception {
		long first = add(101);
		long second = add(102);
		try (var executor = Executors.newSingleThreadExecutor()) {
			new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wishlist_accommodation", Long.class)).isEqualTo(2);
				try {
					executor.submit(() -> service.deleteWishlistAccommodation(first, 10L)).get(20, TimeUnit.SECONDS);
				} catch (Exception exception) {
					throw new AssertionError(exception);
				}
				service.deleteWishlistAccommodation(second, 10L);
			});
		}
		assertAggregate();
	}

	@Test
	void rollbackRestoresMembershipAndAggregateTogether() {
		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			add(101);
			throw new IllegalStateException("intentional rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertAggregate();
	}

	private long add(long accommodationId) {
		return service.createWishlistAccommodation(wishlistId, new WishlistAccommodationRequest.Create(accommodationId), 10L).id();
	}

	private void assertAggregate() {
		long actualCount = jdbc.queryForObject("SELECT COUNT(*) FROM wishlist_accommodation WHERE wishlist_id = ?", Long.class, wishlistId);
		var aggregate = jdbc.queryForMap("SELECT accommodation_count, representative_accommodation_id FROM wishlist WHERE id = ?", wishlistId);
		assertThat(((Number)aggregate.get("accommodation_count")).longValue()).isEqualTo(actualCount);
		var latest = jdbc.queryForList("SELECT accommodation_id FROM wishlist_accommodation WHERE wishlist_id = ? ORDER BY created_at DESC, id DESC LIMIT 1", Long.class, wishlistId);
		assertThat(aggregate.get("representative_accommodation_id")).isEqualTo(latest.isEmpty() ? null : latest.getFirst());
	}

	private List<String> concurrently(List<Callable<String>> actions) throws Exception {
		var ready = new CountDownLatch(actions.size());
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(actions.size())) {
			var futures = actions.stream().map(action -> executor.submit(() -> {
				ready.countDown();
				if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
				try {
					return action.call();
				} catch (BaseException exception) {
					return exception.getErrorCode().getCode();
				} catch (Exception exception) {
					return exception.getClass().getSimpleName();
				}
			})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<String> outcomes = new ArrayList<>();
			for (var future : futures) outcomes.add(future.get(30, TimeUnit.SECONDS));
			return outcomes;
		}
	}
}
