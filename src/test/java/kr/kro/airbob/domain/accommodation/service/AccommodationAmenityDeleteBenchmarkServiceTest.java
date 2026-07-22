package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;
import kr.kro.airbob.common.monitoring.SqlQueryType;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationMonitor;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Measurement;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Variant;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkVerification;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.service.AccommodationAmenityDeleteBenchmarkFixtureService.Fixture;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccommodationAmenity 삭제 Before 벤치마크 오케스트레이터 테스트")
class AccommodationAmenityDeleteBenchmarkServiceTest {

	@Mock private AccommodationService accommodationService;
	@Mock private AccommodationAmenityDeleteBeforeBenchmarkService beforeService;
	@Mock private AccommodationAmenityDeleteBenchmarkFixtureService fixtureService;
	@Mock private BulkOperationMonitor bulkOperationMonitor;
	@Mock private BulkWriteBenchmarkDatabaseGuard databaseGuard;
	@Mock private Fixture fixture;

	private AccommodationAmenityDeleteBenchmarkService benchmarkService;

	@BeforeEach
	void setUp() {
		benchmarkService = new AccommodationAmenityDeleteBenchmarkService(
			accommodationService,
			beforeService,
			fixtureService,
			bulkOperationMonitor,
			databaseGuard
		);
	}

	@Test
	@DisplayName("DB 가드와 fixture 준비 뒤 실제 운영 proxy의 full replacement를 별도 이름으로 측정한다")
	void measuresProductionFullReplacementThroughDistinctOperation() {
		AccommodationRequest.Update replacementRequest = mock(AccommodationRequest.Update.class);
		given(fixtureService.createFixture(7L, 31)).willReturn(fixture);
		given(fixture.targetAccommodationId()).willReturn(101L);
		given(fixture.replacementRequest()).willReturn(replacementRequest);
		given(fixture.activeAmenityCodeCount()).willReturn(30);
		given(fixture.workloadClass()).willReturn(
			AccommodationAmenityDeleteBenchmarkVerification.WorkloadClass.STRESS
		);
		given(fixture.replacementMap()).willReturn(Map.of("WIFI", 2));
		given(bulkOperationMonitor.monitor(
			eq("accommodation-amenity-full-replacement-before"),
			any(Runnable.class)
		)).willAnswer(invocation -> {
			invocation.<Runnable>getArgument(1).run();
			return snapshot("accommodation-amenity-full-replacement-before", 31, 1);
		});
		given(fixtureService.verify(fixture, Measurement.FULL_REPLACEMENT))
			.willReturn(verification(31, 1));

		var response = benchmarkService.run(
			7L,
			new AccommodationAmenityDeleteBenchmarkRequest(
				Variant.BEFORE,
				Measurement.FULL_REPLACEMENT,
				31
			)
		);

		var ordered = inOrder(databaseGuard, fixtureService, bulkOperationMonitor, accommodationService);
		ordered.verify(databaseGuard).verifyReady();
		ordered.verify(fixtureService).createFixture(7L, 31);
		ordered.verify(bulkOperationMonitor).monitor(
			eq("accommodation-amenity-full-replacement-before"),
			any(Runnable.class)
		);
		verify(accommodationService).updateAccommodation(101L, replacementRequest, 7L);
		verify(fixtureService).verify(fixture, Measurement.FULL_REPLACEMENT);
		verify(fixtureService).cleanup(fixture);
		then(beforeService).shouldHaveNoInteractions();
		assertThat(response.measurement()).isEqualTo(Measurement.FULL_REPLACEMENT);
		assertThat(response.workloadClass())
			.isEqualTo(AccommodationAmenityDeleteBenchmarkVerification.WorkloadClass.STRESS);
		assertThat(response.activeAmenityCodeCount()).isEqualTo(30);
		assertThat(response.replacementRowsExpected()).isOne();
		assertThat(response.operation().operationName())
			.isEqualTo("accommodation-amenity-full-replacement-before");
	}

	@Test
	@DisplayName("repository 파생 삭제 진단은 production replacement와 섞지 않는 별도 이름으로 측정한다")
	void measuresDeleteOnlyDiagnosticSeparately() {
		given(fixtureService.createFixture(7L, 3)).willReturn(fixture);
		given(fixture.targetAccommodationId()).willReturn(101L);
		given(fixture.activeAmenityCodeCount()).willReturn(30);
		given(fixture.workloadClass()).willReturn(
			AccommodationAmenityDeleteBenchmarkVerification.WorkloadClass.REALISTIC
		);
		given(fixture.replacementMap()).willReturn(Map.of("WIFI", 1));
		given(bulkOperationMonitor.monitor(
			eq("accommodation-amenity-delete-only-before"),
			any(Runnable.class)
		)).willAnswer(invocation -> {
			invocation.<Runnable>getArgument(1).run();
			return snapshot("accommodation-amenity-delete-only-before", 3, 0);
		});
		given(fixtureService.verify(fixture, Measurement.DELETE_ONLY))
			.willReturn(verification(3, 0));

		var response = benchmarkService.run(
			7L,
			new AccommodationAmenityDeleteBenchmarkRequest(
				Variant.BEFORE,
				Measurement.DELETE_ONLY,
				3
			)
		);

		verify(beforeService).deleteByAccommodationId(101L);
		then(accommodationService).shouldHaveNoInteractions();
		assertThat(response.measurement()).isEqualTo(Measurement.DELETE_ONLY);
		assertThat(response.replacementRowsExpected()).isZero();
		assertThat(response.operation().operationName())
			.isEqualTo("accommodation-amenity-delete-only-before");
	}

	@Test
	@DisplayName("null, 지원하지 않는 variant/measurement, 범위 밖 dataset은 DB 접근 전에 거부한다")
	void rejectsInvalidInputBeforeDatabaseAccess() {
		List<AccommodationAmenityDeleteBenchmarkRequest> invalid = List.of(
			new AccommodationAmenityDeleteBenchmarkRequest(null, Measurement.FULL_REPLACEMENT, 1),
			new AccommodationAmenityDeleteBenchmarkRequest(Variant.BEFORE, null, 1),
			new AccommodationAmenityDeleteBenchmarkRequest(Variant.BEFORE, Measurement.DELETE_ONLY, -1),
			new AccommodationAmenityDeleteBenchmarkRequest(Variant.BEFORE, Measurement.DELETE_ONLY, 101)
		);

		assertThatThrownBy(() -> benchmarkService.run(7L, null))
			.isInstanceOf(IllegalArgumentException.class);
		invalid.forEach(request -> assertThatThrownBy(() -> benchmarkService.run(7L, request))
			.isInstanceOf(IllegalArgumentException.class));

		then(databaseGuard).shouldHaveNoInteractions();
		then(fixtureService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("operation 실패와 cleanup 실패가 겹치면 원래 실패에 cleanup 실패를 억제한다")
	void suppressesCleanupFailureBehindOperationFailure() {
		RuntimeException operationFailure = new RuntimeException("operation failed");
		RuntimeException cleanupFailure = new RuntimeException("cleanup failed");
		given(fixtureService.createFixture(7L, 1)).willReturn(fixture);
		given(fixture.targetAccommodationId()).willReturn(101L);
		given(fixture.replacementRequest()).willReturn(mock(AccommodationRequest.Update.class));
		given(bulkOperationMonitor.monitor(anyString(), any(Runnable.class)))
			.willAnswer(invocation -> {
				invocation.<Runnable>getArgument(1).run();
				return snapshot("unused", 1, 1);
			});
		willThrow(operationFailure).given(accommodationService)
			.updateAccommodation(eq(101L), any(), eq(7L));
		willThrow(cleanupFailure).given(fixtureService).cleanup(fixture);

		Throwable thrown = catchThrowable(() -> benchmarkService.run(
			7L,
			new AccommodationAmenityDeleteBenchmarkRequest(
				Variant.BEFORE,
				Measurement.FULL_REPLACEMENT,
				1
			)
		));

		assertThat(thrown).isSameAs(operationFailure);
		assertThat(thrown.getSuppressed()).containsExactly(cleanupFailure);
		verify(fixtureService).cleanup(fixture);
	}

	private BulkOperationSnapshot snapshot(String name, int deletedRows, int replacementRows) {
		boolean fullReplacement = name.contains("full-replacement");
		return new BulkOperationSnapshot(
			name,
			BulkOperationSnapshot.Outcome.SUCCESS,
			1_000_000,
			Map.of(
				SqlQueryType.SELECT, fullReplacement ? 3 : 1,
				SqlQueryType.INSERT, fullReplacement ? replacementRows + 1 : 0,
				SqlQueryType.UPDATE, fullReplacement ? 1 : 0,
				SqlQueryType.DELETE, deletedRows,
				SqlQueryType.TOTAL, fullReplacement
					? deletedRows + replacementRows + 5
					: deletedRows + 1
			),
			0,
			0,
			null,
			null
		);
	}

	private AccommodationAmenityDeleteBenchmarkVerification verification(
		int deletedRows,
		int replacementRows
	) {
		return new AccommodationAmenityDeleteBenchmarkVerification(
			deletedRows,
			deletedRows,
			replacementRows,
			replacementRows == 0 ? Map.of() : Map.of("WIFI", replacementRows),
			true,
			true,
			true,
			true,
			true
		);
	}
}
