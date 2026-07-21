package kr.kro.airbob.domain.wishlist.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.common.monitoring.SqlQueryType;
import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationMonitor;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest.Variant;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkVerification;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteBenchmarkFixtureService.Fixture;

@ExtendWith(MockitoExtension.class)
@DisplayName("Wishlist 삭제 Before 벤치마크 오케스트레이터 테스트")
class WishlistDeleteBenchmarkServiceTest {

	@Mock private WishlistService wishlistService;
	@Mock private WishlistDeleteBenchmarkFixtureService fixtureService;
	@Mock private BulkOperationMonitor bulkOperationMonitor;
	@Mock private BulkWriteBenchmarkDatabaseGuard databaseGuard;

	private WishlistDeleteBenchmarkService benchmarkService;

	@BeforeEach
	void setUp() {
		benchmarkService = new WishlistDeleteBenchmarkService(
			wishlistService,
			fixtureService,
			bulkOperationMonitor,
			databaseGuard
		);
	}

	@Test
	@DisplayName("실제 운영 서비스를 측정하고 검증한 뒤 fixture를 정리한다")
	void measuresProductionServiceAndCleansFixture() {
		Fixture fixture = fixture(3);
		BulkOperationSnapshot snapshot = snapshot(3);
		WishlistDeleteBenchmarkVerification verification = successfulVerification(3);
		given(fixtureService.createFixture(7L, 3)).willReturn(fixture);
		given(bulkOperationMonitor.monitor(eq("wishlist-delete-before"), any(Runnable.class)))
			.willAnswer(invocation -> {
				invocation.<Runnable>getArgument(1).run();
				return snapshot;
			});
		given(fixtureService.verify(fixture)).willReturn(verification);

		var response = benchmarkService.runBefore(
			7L,
			new WishlistDeleteBenchmarkRequest(Variant.BEFORE, 3)
		);

		verify(wishlistService).deleteWishlist(101L, 7L);
		verify(fixtureService).verify(fixture);
		verify(fixtureService).cleanup(fixture);
		assertThat(response.expectedRows()).isEqualTo(3);
		assertThat(response.verifiedRows()).isEqualTo(3);
		assertThat(response.verificationSucceeded()).isTrue();
		assertThat(response.operation().hibernateStatementsByType())
			.containsEntry(SqlQueryType.DELETE, 3);
	}

	@Test
	@DisplayName("지원하지 않는 variant와 범위 밖 dataset은 fixture 생성 전에 거부한다")
	void rejectsInvalidInputBeforeFixtureCreation() {
		assertThatIllegalArgumentException().isThrownBy(() -> benchmarkService.runBefore(
			7L,
			new WishlistDeleteBenchmarkRequest(null, 3)
		));
		assertThatIllegalArgumentException().isThrownBy(() -> benchmarkService.runBefore(
			7L,
			new WishlistDeleteBenchmarkRequest(Variant.BEFORE, 1001)
		));
		assertThatIllegalArgumentException().isThrownBy(() -> benchmarkService.runBefore(
			7L,
			new WishlistDeleteBenchmarkRequest(Variant.BEFORE, -1)
		));

		then(fixtureService).shouldHaveNoInteractions();
		then(bulkOperationMonitor).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("운영 삭제가 실패해도 정리하며 정리 실패는 원래 예외를 가리지 않는다")
	void preservesOperationFailureWhenCleanupAlsoFails() {
		Fixture fixture = fixture(1);
		RuntimeException operationFailure = new RuntimeException("operation failed");
		RuntimeException cleanupFailure = new RuntimeException("cleanup failed");
		given(fixtureService.createFixture(7L, 1)).willReturn(fixture);
		given(bulkOperationMonitor.monitor(eq("wishlist-delete-before"), any(Runnable.class)))
			.willAnswer(invocation -> {
				invocation.<Runnable>getArgument(1).run();
				return snapshot(1);
			});
		willThrow(operationFailure).given(wishlistService).deleteWishlist(101L, 7L);
		willThrow(cleanupFailure).given(fixtureService).cleanup(fixture);

		Throwable thrown = catchThrowable(() -> benchmarkService.runBefore(
			7L,
			new WishlistDeleteBenchmarkRequest(Variant.BEFORE, 1)
		));

		assertThat(thrown).isSameAs(operationFailure);
		assertThat(thrown.getSuppressed()).containsExactly(cleanupFailure);
		verify(fixtureService).cleanup(fixture);
	}

	@Test
	@DisplayName("운영 삭제와 검증이 성공한 뒤 정리가 실패하면 정리 예외를 전달한다")
	void propagatesCleanupFailureAfterSuccessfulOperation() {
		Fixture fixture = fixture(1);
		RuntimeException cleanupFailure = new RuntimeException("cleanup failed");
		given(fixtureService.createFixture(7L, 1)).willReturn(fixture);
		given(bulkOperationMonitor.monitor(eq("wishlist-delete-before"), any(Runnable.class)))
			.willReturn(snapshot(1));
		given(fixtureService.verify(fixture)).willReturn(successfulVerification(1));
		willThrow(cleanupFailure).given(fixtureService).cleanup(fixture);

		assertThatThrownBy(() -> benchmarkService.runBefore(
			7L,
			new WishlistDeleteBenchmarkRequest(Variant.BEFORE, 1)
		)).isSameAs(cleanupFailure);

		verify(fixtureService).cleanup(fixture);
	}

	private Fixture fixture(int datasetSize) {
		return new Fixture(
			7L,
			datasetSize,
			101L,
			102L,
			datasetSize == 0 ? null : 203L,
			201L,
			datasetSize == 0 ? java.util.List.of() : java.util.List.of(301L),
			302L,
			java.util.List.of(201L, 202L, 203L),
			LocalDateTime.of(2026, 7, 21, 12, 0),
			7L
		);
	}

	private BulkOperationSnapshot snapshot(int deletedRows) {
		return new BulkOperationSnapshot(
			"wishlist-delete-before",
			BulkOperationSnapshot.Outcome.SUCCESS,
			2_000_000,
			Map.of(
				SqlQueryType.SELECT, 2,
				SqlQueryType.UPDATE, 1,
				SqlQueryType.DELETE, deletedRows,
				SqlQueryType.TOTAL, 3 + deletedRows
			),
			0,
			0,
			null,
			null
		);
	}

	private WishlistDeleteBenchmarkVerification successfulVerification(int rows) {
		return new WishlistDeleteBenchmarkVerification(rows, true, true, true, true, true, true, true);
	}
}
