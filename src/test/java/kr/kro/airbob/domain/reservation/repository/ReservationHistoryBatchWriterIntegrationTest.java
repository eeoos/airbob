package kr.kro.airbob.domain.reservation.repository;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.IntStream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

@JdbcTest(properties = {
	"spring.flyway.enabled=false",
	"reservation.expiration.history-batch-size=2"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReservationHistoryBatchWriter.class)
@Testcontainers
@ResourceLock("jvm-default-time-zone")
@DisplayName("ReservationHistory JDBC batch writer MySQL integration test")
class ReservationHistoryBatchWriterIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_reservation_history_writer");

	@DynamicPropertySource
	static void setProperties(DynamicPropertyRegistry registry) {
		Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.baselineOnMigrate(true)
			.baselineVersion("1")
			.load()
			.migrate();

		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
	}

	@Autowired private ReservationHistoryBatchWriter writer;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private TransactionTemplate transactionTemplate;

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3, 5})
	@DisplayName("제출한 모든 history를 정확히 저장한다")
	void persistsEverySubmittedHistory(int size) {
		Instant historyCreatedAt = Instant.parse("2026-07-21T12:30:00Z");

		writer.writeAll(histories(size), historyCreatedAt);

		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM reservation_history",
			Long.class
		)).isEqualTo((long) size);
	}

	@Test
	@DisplayName("JVM 기본 시간대와 무관하게 24개 non-ID snapshot column을 UTC 기준으로 저장한다")
	void persistsAllSnapshotColumns() {
		Instant historyCreatedAt = Instant.parse("2026-07-21T12:30:00Z");
		ReservationHistory history = history(1);
		TimeZone originalTimeZone = TimeZone.getDefault();

		try {
			TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
			writer.writeAll(List.of(history), historyCreatedAt);
		} finally {
			TimeZone.setDefault(originalTimeZone);
		}

		Map<String, Object> row = jdbcTemplate.queryForMap(
			"SELECT * FROM reservation_history WHERE reservation_id = ?",
			history.getReservationId()
		);
		assertThat(row)
			.containsEntry("reservation_id", history.getReservationId())
			.containsEntry("reservation_uid", history.getReservationUid())
			.containsEntry("reservation_code", history.getReservationCode())
			.containsEntry("accommodation_id", history.getAccommodationId())
			.containsEntry("guest_id", history.getGuestId())
			.containsEntry("time_zone_id", history.getTimeZoneId())
			.containsEntry("guest_count", history.getGuestCount())
			.containsEntry("total_price", history.getTotalPrice())
			.containsEntry("currency", history.getCurrency())
			.containsEntry("status", history.getStatus().name())
			.containsEntry("message", history.getMessage())
			.containsEntry("created_by", history.getCreatedBy())
			.containsEntry("history_created_by", null)
			.containsEntry("change_type", history.getChangeType().name())
			.containsEntry("change_reason", history.getChangeReason())
			.containsEntry("source_system", history.getSourceSystem())
			.containsEntry("client_ip", null);
		assertThat(asLocalDate(row.get("check_in_date"))).isEqualTo(history.getCheckInDate());
		assertThat(asLocalDate(row.get("check_out_date"))).isEqualTo(history.getCheckOutDate());
		assertThat(readDateTime(history.getReservationId(), "check_in_at"))
			.isEqualTo(LocalDateTime.ofInstant(history.getCheckInAt(), ZoneOffset.UTC));
		assertThat(readDateTime(history.getReservationId(), "check_out_at"))
			.isEqualTo(LocalDateTime.ofInstant(history.getCheckOutAt(), ZoneOffset.UTC));
		assertThat(readDateTime(history.getReservationId(), "expires_at"))
			.isEqualTo(LocalDateTime.ofInstant(history.getExpiresAt(), ZoneOffset.UTC));
		assertThat(readDateTime(history.getReservationId(), "created_at"))
			.isEqualTo(history.getCreatedAt());
		assertThat(readDateTime(history.getReservationId(), "history_created_at"))
			.isEqualTo(LocalDateTime.ofInstant(historyCreatedAt, ZoneOffset.UTC));
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("outer transaction에 참여하고 rollback한다")
	void joinsAndRollsBackTheOuterTransaction() {
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			writer.writeAll(histories(5), Instant.parse("2026-07-21T12:30:00Z"));
			throw new IntentionalRollback();
		})).isInstanceOf(IntentionalRollback.class);

		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM reservation_history",
			Long.class
		)).isZero();
	}

	private List<ReservationHistory> histories(int size) {
		return IntStream.range(0, size)
			.mapToObj(this::history)
			.toList();
	}

	private ReservationHistory history(int index) {
		return ReservationHistory.builder()
			.reservationId((long) index + 1)
			.reservationUid(UUID.nameUUIDFromBytes(
				("reservation-" + index).getBytes(StandardCharsets.UTF_8)
			).toString())
			.reservationCode("R" + index)
			.accommodationId(100L + index)
			.guestId(200L + index)
			.checkInDate(LocalDate.of(2026, 8, 1).plusDays(index))
			.checkOutDate(LocalDate.of(2026, 8, 2).plusDays(index))
			.checkInAt(LocalDateTime.of(2026, 8, 1, 15, 0).plusDays(index).toInstant(ZoneOffset.UTC))
			.checkOutAt(LocalDateTime.of(2026, 8, 2, 11, 0).plusDays(index).toInstant(ZoneOffset.UTC))
			.timeZoneId("UTC")
			.guestCount(2)
			.totalPrice(100_000L + index)
			.currency("KRW")
			.status(ReservationStatus.EXPIRED)
			.message("snapshot-" + index)
			.expiresAt(Instant.parse("2026-07-21T11:00:00Z"))
			.createdAt(LocalDateTime.of(2026, 7, 1, 9, 0))
			.createdBy(200L + index)
			.changeType(ChangeType.STATUS_CHANGE)
			.changeReason("결제 시간 초과")
			.sourceSystem("BATCH")
			.clientIp(null)
			.build();
	}

	private LocalDate asLocalDate(Object value) {
		if (value instanceof java.sql.Date date) {
			return date.toLocalDate();
		}
		return (LocalDate) value;
	}

	private LocalDateTime readDateTime(long reservationId, String column) {
		return jdbcTemplate.queryForObject(
			"SELECT " + column + " FROM reservation_history WHERE reservation_id = ?",
			(resultSet, rowNumber) -> resultSet.getObject(column, LocalDateTime.class),
			reservationId
		);
	}

	private static class IntentionalRollback extends RuntimeException {
	}
}
