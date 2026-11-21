package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.zaxxer.hikari.HikariDataSource;

import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventoryDayRepository.SeedDay;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;

@Testcontainers
@DisplayName("MySQL 8.4 inventory batch seed and retention")
class AccommodationInventoryBatchSeedIntegrationTest {

	private static final int HOSTS = 1_000;
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-11T03:00:00Z"), ZoneOffset.UTC);
	private static final AccommodationInventorySeedPolicy POLICY =
		new AccommodationInventorySeedPolicy(new BookingWindowProvider(CLOCK), 7);
	private static final AccommodationInventorySeedPolicy.SeedRange RANGE =
		POLICY.currentRange("Asia/Seoul", CLOCK.instant());
	private static final List<Long> IDS = LongStream.rangeClosed(1, HOSTS).boxed().toList();

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
		.withDatabaseName("airbobdb_inventory_batch")
		.withUsername("airbob")
		.withPassword("airbob");

	private static HikariDataSource dataSource;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void setUpDatabase() {
		Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration").load().migrate();
		dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(MYSQL.getJdbcUrl());
		dataSource.setUsername(MYSQL.getUsername());
		dataSource.setPassword(MYSQL.getPassword());
		dataSource.setMaximumPoolSize(4);
		jdbc = new JdbcTemplate(dataSource);
		jdbc.update("""
			INSERT INTO member (id, email, nickname, role, status, updated_at)
			VALUES (1, 'inventory-batch@test.invalid', 'inventory-batch', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		jdbc.batchUpdate("""
			INSERT INTO accommodation (
			  id, member_id, base_price, currency, check_in_time, check_out_time,
			  accommodation_uid, time_zone_id, updated_at, status
			) VALUES (?, 1, 100000, 'KRW', '15:00:00', '11:00:00',
			          UUID_TO_BIN(UUID()), 'Asia/Seoul', NOW(6), 'PUBLISHED')
			""", IDS.stream().map(id -> new Object[] {id}).toList());
	}

	@AfterAll
	static void closePool() {
		if (dataSource != null) {
			dataSource.close();
		}
	}

	@BeforeEach
	void clearInventory() {
		jdbc.update("DELETE FROM accommodation_inventory_day");
	}

	@Test
	@DisplayName("batch seed bounds each INSERT and skips existing inventory on repeated calls")
	void seedsMissingInventoryInBoundedBatchesWithoutDuplicateWrites() {
		CountingRepository repository = new CountingRepository(jdbc);
		AccommodationInventorySeedService seed = service(repository, IDS);

		AccommodationInventorySeedService.SeedBatch initial = seed.seedNextPublishedBatch(0, HOSTS);

		assertThat(initial.missingDaysSubmitted()).isEqualTo(initial.expectedDays());
		assertThat(initial.insertStatements()).isEqualTo((int)((initial.expectedDays() + 999) / 1000));
		assertThat(repository.largestInsert).isEqualTo(1000);

		repository.resetCounts();
		AccommodationInventorySeedService.SeedBatch repeated = seed.seedNextPublishedBatch(0, HOSTS);

		assertThat(repeated.missingDaysSubmitted()).isZero();
		assertThat(repeated.insertStatements()).isZero();
		assertThat(repository.returnedRows).isEqualTo(HOSTS);
		assertThat(repository.queries).isOne();
		assertThat(repository.countSeedDays(IDS, RANGE.startInclusive(), RANGE.endExclusive()))
			.hasSize(HOSTS).allSatisfy((id, days) -> assertThat(days).isEqualTo(
				(int)RANGE.startInclusive().datesUntil(RANGE.endExclusive()).count()));
	}

	@Test
	@DisplayName("retention deletes every past FREE row without exceeding the batch limit")
	void deletesPastFreeRowsInBoundedBatches() {
		AccommodationInventoryDayRepository repository = new AccommodationInventoryDayRepository(jdbc);
		AccommodationInventoryRetentionService retention = new AccommodationInventoryRetentionService(repository);
		LocalDate start = RANGE.startInclusive().minusDays(60);
		for (int batchSize : List.of(1000, 5000)) {
			for (int day = 0; day < 10; day++) {
				LocalDate date = start.plusDays(day);
				repository.seedMissingDayBatch(IDS.stream().map(id -> new SeedDay(id, date)).toList());
			}
			long deletedRows = 0;
			int deleted;
			do {
				deleted = transaction().execute(status -> retention.deleteNextPastFreeBatch(
					start.plusDays(10), batchSize));
				deletedRows += deleted;
				assertThat(deleted).isBetween(0, batchSize);
			} while (deleted == batchSize);
			assertThat(deletedRows).isEqualTo(10_000);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accommodation_inventory_day", Long.class))
				.isZero();
		}
	}

	@Test
	@DisplayName("count coverage detects interior holes and ignores rows outside the local horizon")
	void repairsInteriorHolesWithExactDateBoundaries() {
		AccommodationInventoryDayRepository repository = new AccommodationInventoryDayRepository(jdbc);
		AccommodationInventorySeedService seed = service(repository, List.of(1L));
		seed.seedNextPublishedBatch(0, 1000);
		LocalDate hole = RANGE.startInclusive().plusDays(20);
		jdbc.update("DELETE FROM accommodation_inventory_day WHERE accommodation_id = 1 AND stay_date = ?",
			Date.valueOf(hole));
		repository.seedMissingDays(1L, List.of(RANGE.startInclusive().minusDays(1), RANGE.endExclusive()));

		AccommodationInventorySeedService.SeedBatch batch = seed.seedNextPublishedBatch(0, 1000);

		assertThat(batch.missingDaysSubmitted()).isOne();
		assertThat(batch.insertStatements()).isOne();
		assertThat(repository.findSeedDays(List.of(1L), RANGE.startInclusive(), RANGE.endExclusive()))
			.contains(new SeedDay(1L, hole));
	}

	@Test
	@DisplayName("simultaneous batch seeds converge after both observe the same missing dates")
	void concurrentBatchSeedsConverge() throws Exception {
		CountDownLatch snapshots = new CountDownLatch(2);
		AtomicInteger readers = new AtomicInteger();
		AccommodationInventoryDayRepository repository = new AccommodationInventoryDayRepository(jdbc) {
			@Override
			public List<SeedDay> findSeedDays(List<Long> ids, LocalDate start, LocalDate end) {
				List<SeedDay> result = super.findSeedDays(ids, start, end);
				readers.incrementAndGet();
				snapshots.countDown();
				try {
					if (!snapshots.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("concurrent batch snapshots timed out");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				return result;
			}
		};
		List<Long> targets = IDS.subList(0, 21);
		AccommodationInventorySeedService seed = service(repository, targets);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> seed.seedNextPublishedBatch(0, 1000));
			var second = executor.submit(() -> seed.seedNextPublishedBatch(0, 1000));
			assertThat(first.get(30, TimeUnit.SECONDS).processed()).isEqualTo(targets.size());
			assertThat(second.get(30, TimeUnit.SECONDS).processed()).isEqualTo(targets.size());
		}
		assertThat(readers).hasValue(2);
		assertThat(repository.countSeedDays(targets, RANGE.startInclusive(), RANGE.endExclusive()))
			.hasSize(targets.size()).allSatisfy((id, days) -> assertThat(days).isEqualTo(
				(int)RANGE.startInclusive().datesUntil(RANGE.endExclusive()).count()));
	}

	@Test
	@DisplayName("multi-statement single-accommodation seed rolls back with the enclosing business transaction")
	void publicationRollbackIncludesEveryInsertChunk() {
		AccommodationInventoryDayRepository repository = new AccommodationInventoryDayRepository(jdbc);
		ReservationInventoryService inventory = new ReservationInventoryService(repository);
		assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
			inventory.seed(1L, RANGE.startInclusive(), RANGE.startInclusive().plusDays(1001));
			throw new IllegalStateException("publication rejected");
		})).isInstanceOf(IllegalStateException.class).hasMessage("publication rejected");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accommodation_inventory_day", Long.class))
			.isZero();
	}

	private static TransactionTemplate transaction() {
		TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		return template;
	}

	private AccommodationInventorySeedService service(
		AccommodationInventoryDayRepository repository, List<Long> ids
	) {
		AccommodationRepository accommodations = mock(AccommodationRepository.class);
		given(accommodations.findInventorySeedTargets(any(AccommodationStatus.class), anyLong(), any(Pageable.class)))
			.willAnswer(invocation -> ids.stream()
				.filter(id -> id > invocation.<Long>getArgument(1))
				.limit(invocation.<Pageable>getArgument(2).getPageSize())
				.map(id -> new AccommodationRepository.InventorySeedTarget() {
					@Override public Long getAccommodationId() { return id; }
					@Override public String getTimeZoneId() { return "Asia/Seoul"; }
				}).toList());
		return new AccommodationInventorySeedService(accommodations,
			new ReservationInventoryService(repository), repository, POLICY, CLOCK);
	}

	private static class CountingRepository extends AccommodationInventoryDayRepository {
		private long queries;
		private long returnedRows;
		private int largestInsert;

		CountingRepository(JdbcTemplate jdbcTemplate) { super(jdbcTemplate); }

		void resetCounts() { queries = 0; returnedRows = 0; largestInsert = 0; }

		@Override
		public Map<Long, Integer> countSeedDays(List<Long> ids, LocalDate start, LocalDate end) {
			Map<Long, Integer> result = super.countSeedDays(ids, start, end);
			queries++;
			returnedRows += result.size();
			return result;
		}

		@Override
		public List<SeedDay> findSeedDays(List<Long> ids, LocalDate start, LocalDate end) {
			List<SeedDay> result = super.findSeedDays(ids, start, end);
			queries++;
			returnedRows += result.size();
			return result;
		}

		@Override
		public void seedMissingDayBatch(List<SeedDay> days) {
			largestInsert = Math.max(largestInsert, days.size());
			super.seedMissingDayBatch(days);
		}
	}

}
