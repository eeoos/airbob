package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.monitoring.SqlQueryType;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationMonitor;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest.Variant;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkVerification;
import kr.kro.airbob.domain.reservation.scheduler.ReservationScheduler;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkFixtureService.Fixture;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkHoldService.HoldRemovalSnapshot;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationHistory IDENTITY INSERT Before 벤치마크 서비스 테스트")
class ReservationHistoryInsertBenchmarkServiceTest {

	@Mock private ReservationScheduler reservationScheduler;
	@Mock private ReservationHistoryInsertBenchmarkFixtureService fixtureService;
	@Mock private ReservationHistoryInsertBenchmarkHoldService holdService;
	@Mock private BulkOperationMonitor bulkOperationMonitor;
	@Mock private BulkWriteBenchmarkDatabaseGuard databaseGuard;
	@Mock private Fixture fixture;
	@Mock private HoldRemovalSnapshot holdSnapshot;
	@Mock private ReservationHistoryInsertBenchmarkVerification verification;

	private ReservationHistoryInsertBenchmarkService benchmarkService;

	@BeforeEach
	void setUp() {
		benchmarkService = new ReservationHistoryInsertBenchmarkService(
			reservationScheduler,
			fixtureService,
			holdService,
			bulkOperationMonitor,
			databaseGuard
		);
	}

	@AfterEach
	void clearUserContext() {
		UserContext.clear();
	}

	@Test
	@DisplayName("fixture 밖의 실제 scheduler 호출만 측정하고 관리자 context를 복원한다")
	void measuresSchedulerTransactionAndRestoresRequestContext() {
		UserInfo requestAdmin = new UserInfo(7L, "127.0.0.1", "HTTP");
		UserContext.set(requestAdmin);
		BulkOperationSnapshot snapshot = beforeSnapshot(3);
		given(fixtureService.createFixture(3)).willReturn(fixture);
		given(holdService.finishRecording()).willReturn(holdSnapshot);
		given(holdSnapshot.callCount()).willReturn(3);
		given(fixtureService.verify(fixture, holdSnapshot)).willReturn(verification);
		given(verification.verifiedRows()).willReturn(3L);
		given(verification.succeeded()).willReturn(true);
		given(bulkOperationMonitor.monitor(
			eq(ReservationHistoryInsertBenchmarkService.BEFORE_OPERATION_NAME),
			any(Runnable.class)
		)).willAnswer(invocation -> {
			assertThat(UserContext.get()).isNull();
			invocation.<Runnable>getArgument(1).run();
			return snapshot;
		});

		var response = benchmarkService.run(
			new ReservationHistoryInsertBenchmarkRequest(Variant.BEFORE, 3)
		);

		assertThat(UserContext.get()).isSameAs(requestAdmin);
		assertThat(response.variant()).isEqualTo(Variant.BEFORE);
		assertThat(response.expectedRows()).isEqualTo(3);
		assertThat(response.verifiedRows()).isEqualTo(3);
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.holdRemovalCalls()).isEqualTo(3);
		assertThat(response.redisNetworkExcluded()).isTrue();
		assertThat(response.operation().operationName())
			.isEqualTo(ReservationHistoryInsertBenchmarkService.BEFORE_OPERATION_NAME);

		then(databaseGuard).should().verifyReady();
		then(fixtureService).should().createFixture(3);
		then(holdService).should().startRecording();
		then(reservationScheduler).should().cleanupExpiredPendingReservation();
		then(holdService).should().finishRecording();
		then(fixtureService).should().verify(fixture, holdSnapshot);
		then(fixtureService).should().cleanup(fixture);
	}

	@Test
	@DisplayName("잘못된 입력은 database guard와 fixture 생성 전에 거부한다")
	void rejectsInvalidInputBeforeDatabaseAccess() {
		assertThatIllegalArgumentException().isThrownBy(() -> benchmarkService.run(null));
		assertThatIllegalArgumentException().isThrownBy(() -> benchmarkService.run(
			new ReservationHistoryInsertBenchmarkRequest(null, 1)
		));
		assertThatIllegalArgumentException().isThrownBy(() -> benchmarkService.run(
			new ReservationHistoryInsertBenchmarkRequest(Variant.BEFORE, -1)
		));
		assertThatIllegalArgumentException().isThrownBy(() -> benchmarkService.run(
			new ReservationHistoryInsertBenchmarkRequest(Variant.BEFORE, 2001)
		));

		then(databaseGuard).shouldHaveNoInteractions();
		then(fixtureService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("scheduler와 cleanup이 함께 실패하면 원래 실패를 보존하고 context를 복원한다")
	void preservesOperationFailureWhenCleanupAlsoFails() {
		UserInfo requestAdmin = new UserInfo(7L, "127.0.0.1", "HTTP");
		UserContext.set(requestAdmin);
		RuntimeException operationFailure = new RuntimeException("scheduler failed");
		RuntimeException cleanupFailure = new RuntimeException("cleanup failed");
		given(fixtureService.createFixture(2)).willReturn(fixture);
		given(bulkOperationMonitor.monitor(
			eq(ReservationHistoryInsertBenchmarkService.BEFORE_OPERATION_NAME),
			any(Runnable.class)
		)).willAnswer(invocation -> {
			invocation.<Runnable>getArgument(1).run();
			return beforeSnapshot(2);
		});
		willThrow(operationFailure).given(reservationScheduler).cleanupExpiredPendingReservation();
		willThrow(cleanupFailure).given(fixtureService).cleanup(fixture);

		Throwable thrown = catchThrowable(() -> benchmarkService.run(
			new ReservationHistoryInsertBenchmarkRequest(Variant.BEFORE, 2)
		));

		assertThat(thrown).isSameAs(operationFailure);
		assertThat(thrown.getSuppressed()).containsExactly(cleanupFailure);
		assertThat(UserContext.get()).isSameAs(requestAdmin);
		then(holdService).should().clearRecording();
		then(fixtureService).should().cleanup(fixture);
	}

	private BulkOperationSnapshot beforeSnapshot(int rows) {
		return new BulkOperationSnapshot(
			ReservationHistoryInsertBenchmarkService.BEFORE_OPERATION_NAME,
			BulkOperationSnapshot.Outcome.SUCCESS,
			2_000_000,
			Map.of(
				SqlQueryType.SELECT, 1,
				SqlQueryType.INSERT, rows,
				SqlQueryType.UPDATE, rows,
				SqlQueryType.DELETE, 0,
				SqlQueryType.OTHER, 0,
				SqlQueryType.TOTAL, 1 + (rows * 2)
			),
			0,
			0,
			null,
			null
		);
	}
}
