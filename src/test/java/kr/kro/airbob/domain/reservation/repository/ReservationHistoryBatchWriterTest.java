package kr.kro.airbob.domain.reservation.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationContext;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationContextHolder;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationHistory JDBC batch writer unit test")
class ReservationHistoryBatchWriterTest {
	private static final Instant HISTORY_CREATED_AT = Instant.parse("2026-07-21T12:00:00Z");

	@Mock private JdbcTemplate jdbcTemplate;
	@Mock private PreparedStatement statement;
	@Captor private ArgumentCaptor<BatchPreparedStatementSetter> batchSetterCaptor;

	@AfterEach
	void tearDown() {
		BulkOperationContextHolder.clear();
	}

	@Test
	@DisplayName("0 이하 batch 크기를 거부한다")
	void rejectsNonPositiveBatchSize() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ReservationHistoryBatchWriter(jdbcTemplate, 0));
	}

	@Test
	@DisplayName("5행을 batch 크기 2로 제출하면 3회와 5행을 기록한다")
	void recordsChunkStatistics() {
		given(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
			.willReturn(new int[] {1, 1}, new int[] {1, 1}, new int[] {1});
		ReservationHistoryBatchWriter writer = new ReservationHistoryBatchWriter(jdbcTemplate, 2);
		BulkOperationContext context = new BulkOperationContext("expired-reservation-cleanup-after");
		BulkOperationContextHolder.initContext(context);

		writer.writeAll(histories(5), HISTORY_CREATED_AT);
		BulkOperationSnapshot snapshot = context.snapshot(BulkOperationSnapshot.Outcome.SUCCESS, 1L);

		assertThat(snapshot.jdbcBatchCalls()).isEqualTo(3);
		assertThat(snapshot.jdbcSubmittedRows()).isEqualTo(5);
		assertThat(snapshot.jdbcConfiguredBatchSize()).isEqualTo(2);
		assertThat(snapshot.jdbcAffectedRows()).isEqualTo(5);
	}

	@Test
	@DisplayName("SUCCESS_NO_INFO가 포함되면 affected rows를 알 수 없음으로 기록한다")
	void recordsUnknownAffectedRows() {
		given(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
			.willReturn(new int[] {Statement.SUCCESS_NO_INFO});
		ReservationHistoryBatchWriter writer = new ReservationHistoryBatchWriter(jdbcTemplate, 2);
		BulkOperationContext context = new BulkOperationContext("expired-reservation-cleanup-after");
		BulkOperationContextHolder.initContext(context);

		writer.writeAll(histories(1), HISTORY_CREATED_AT);

		assertThat(context.snapshot(BulkOperationSnapshot.Outcome.SUCCESS, 1L).jdbcAffectedRows())
			.isNull();
	}

	@Test
	@DisplayName("EXECUTE_FAILED 결과는 데이터 무결성 예외로 전환한다")
	void rejectsExecuteFailedResult() {
		given(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
			.willReturn(new int[] {Statement.EXECUTE_FAILED});
		ReservationHistoryBatchWriter writer = new ReservationHistoryBatchWriter(jdbcTemplate, 2);
		BulkOperationContext context = new BulkOperationContext("expired-reservation-cleanup-after");
		BulkOperationContextHolder.initContext(context);

		assertThatThrownBy(() -> writer.writeAll(histories(1), HISTORY_CREATED_AT))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(context.snapshot(BulkOperationSnapshot.Outcome.FAILURE, 1L).jdbcBatchCalls()).isZero();
	}

	@Test
	@DisplayName("제출 행과 다른 update count 길이는 해당 chunk를 기록하지 않고 실패한다")
	void rejectsMismatchedUpdateCounts() {
		given(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
			.willReturn(new int[] {1});
		ReservationHistoryBatchWriter writer = new ReservationHistoryBatchWriter(jdbcTemplate, 2);
		BulkOperationContext context = new BulkOperationContext("expired-reservation-cleanup-after");
		BulkOperationContextHolder.initContext(context);

		assertThatThrownBy(() -> writer.writeAll(histories(2), HISTORY_CREATED_AT))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(context.snapshot(BulkOperationSnapshot.Outcome.FAILURE, 1L).jdbcBatchCalls()).isZero();
	}

	@Test
	@DisplayName("빈 입력은 JDBC 호출을 만들지 않는다")
	void skipsEmptyInput() {
		ReservationHistoryBatchWriter writer = new ReservationHistoryBatchWriter(jdbcTemplate, 2);

		writer.writeAll(List.of(), HISTORY_CREATED_AT);

		then(jdbcTemplate).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("nullable snapshot 값은 JDBC null 타입으로 바인딩한다")
	void bindsNullableSnapshotValuesWithJdbcNullTypes() throws Exception {
		given(jdbcTemplate.batchUpdate(anyString(), batchSetterCaptor.capture())).willReturn(new int[] {1});
		ReservationHistoryBatchWriter writer = new ReservationHistoryBatchWriter(jdbcTemplate, 2);
		ReservationHistory history = ReservationHistory.builder()
			.reservationId(1L)
			.changeType(ChangeType.STATUS_CHANGE)
			.build();

		writer.writeAll(List.of(history), HISTORY_CREATED_AT);
		batchSetterCaptor.getValue().setValues(statement, 0);

		then(statement).should().setNull(4, Types.BIGINT);
		then(statement).should().setNull(5, Types.BIGINT);
		then(statement).should().setNull(6, Types.DATE);
		then(statement).should().setNull(7, Types.DATE);
		then(statement).should().setNull(8, Types.TIMESTAMP);
		then(statement).should().setNull(9, Types.TIMESTAMP);
		then(statement).should().setNull(11, Types.INTEGER);
		then(statement).should().setNull(12, Types.BIGINT);
		then(statement).should().setNull(16, Types.TIMESTAMP);
		then(statement).should().setNull(17, Types.TIMESTAMP);
		then(statement).should().setNull(18, Types.BIGINT);
		then(statement).should().setNull(20, Types.BIGINT);
		then(statement).should().setTimestamp(19, Timestamp.from(HISTORY_CREATED_AT));
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
}
