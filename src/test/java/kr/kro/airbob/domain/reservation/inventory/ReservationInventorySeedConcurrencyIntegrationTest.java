package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("실제 MySQL inventory concurrent seed")
class ReservationInventorySeedConcurrencyIntegrationTest {

	private static final long MEMBER_ID = 81_001L;
	private static final long ACCOMMODATION_ID = 82_001L;
	private static final LocalDate START = LocalDate.of(2026, 9, 10);
	private static final LocalDate END_EXCLUSIVE = START.plusDays(3);

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_inventory_seed_concurrency")
		.withUsername("airbob")
		.withPassword("airbob");

	private static DriverManagerDataSource dataSource;
	private static JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void setUpDatabase() {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		dataSource = new DriverManagerDataSource(
			MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);
		insertAccommodationFixture();
	}

	@Test
	@DisplayName("two independent READ_COMMITTED seeds converge on exact FREE coverage")
	void concurrentSeedsBothSucceedAndLeaveExactCoverage() throws Exception {
		FirstSnapshotBarrierRepository repository = new FirstSnapshotBarrierRepository(
			jdbcTemplate, 2);
		ReservationInventoryService inventoryService = new ReservationInventoryService(repository);
		TransactionTemplate transactionTemplate = readCommittedTransactionTemplate();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);

		Future<?> first = executor.submit(() -> seedAfterStart(
			start, transactionTemplate, inventoryService));
		Future<?> second = executor.submit(() -> seedAfterStart(
			start, transactionTemplate, inventoryService));

		try {
			start.countDown();
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(repository.firstSnapshotReaderCount()).isEqualTo(2);
		AccommodationInventoryDayRepository verifier =
			new AccommodationInventoryDayRepository(jdbcTemplate);
		assertThat(verifier.findSnapshot(ACCOMMODATION_ID, START, END_EXCLUSIVE))
			.containsExactly(
				free(START),
				free(START.plusDays(1)),
				free(START.plusDays(2))
			);
	}

	private TransactionTemplate readCommittedTransactionTemplate() {
		TransactionTemplate template = new TransactionTemplate(
			new DataSourceTransactionManager(dataSource));
		template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		return template;
	}

	private void seedAfterStart(
		CountDownLatch start,
		TransactionTemplate transactionTemplate,
		ReservationInventoryService inventoryService
	) {
		await(start);
		transactionTemplate.executeWithoutResult(status -> inventoryService.seed(
			ACCOMMODATION_ID, START, END_EXCLUSIVE));
	}

	private static AccommodationInventoryDay free(LocalDate stayDate) {
		return new AccommodationInventoryDay(
			ACCOMMODATION_ID,
			stayDate,
			AccommodationInventoryState.FREE,
			null,
			null
		);
	}

	private static void insertAccommodationFixture() {
		jdbcTemplate.update("""
			INSERT INTO member (id, email, nickname, role, status, updated_at)
			VALUES (?, 'inventory-seed-race@test.com', 'inventory-seed-race',
			        'MEMBER', 'ACTIVE', NOW(6))
			""", MEMBER_ID);
		jdbcTemplate.update("""
			INSERT INTO accommodation (
			  id, member_id, base_price, currency, check_in_time, check_out_time,
			  accommodation_uid, time_zone_id, updated_at, status
			) VALUES (
			  ?, ?, 100000, 'KRW', '15:00:00', '11:00:00',
			  UUID_TO_BIN('cb7a7236-a605-455d-ac26-c45ed04f4bbb'),
			  'Asia/Seoul', NOW(6), 'PUBLISHED'
			)
			""", ACCOMMODATION_ID, MEMBER_ID);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting for concurrent seed gate");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("concurrent seed interrupted", exception);
		}
	}

	private static final class FirstSnapshotBarrierRepository
		extends AccommodationInventoryDayRepository {

		private final CountDownLatch firstSnapshots;
		private final Set<Thread> firstSnapshotReaders = ConcurrentHashMap.newKeySet();

		private FirstSnapshotBarrierRepository(
			JdbcTemplate jdbcTemplate,
			int expectedReaders
		) {
			super(jdbcTemplate);
			this.firstSnapshots = new CountDownLatch(expectedReaders);
		}

		@Override
		public List<AccommodationInventoryDay> findSnapshot(
			Long accommodationId,
			LocalDate startInclusive,
			LocalDate endExclusive
		) {
			List<AccommodationInventoryDay> snapshot = super.findSnapshot(
				accommodationId, startInclusive, endExclusive);
			if (firstSnapshotReaders.add(Thread.currentThread())) {
				firstSnapshots.countDown();
				await(firstSnapshots);
			}
			return snapshot;
		}

		private int firstSnapshotReaderCount() {
			return firstSnapshotReaders.size();
		}
	}
}
