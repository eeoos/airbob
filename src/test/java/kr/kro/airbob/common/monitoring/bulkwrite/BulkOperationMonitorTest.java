package kr.kro.airbob.common.monitoring.bulkwrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.common.monitoring.QueryCountContext;
import kr.kro.airbob.common.monitoring.QueryCountContextHolder;
import kr.kro.airbob.common.monitoring.QueryCountSnapshot;
import kr.kro.airbob.common.monitoring.SqlQueryStatementInspector;
import kr.kro.airbob.common.monitoring.SqlQueryType;

@DisplayName("벌크 연산 모니터 테스트")
class BulkOperationMonitorTest {

	@AfterEach
	void tearDown() {
		BulkOperationContextHolder.clear();
		QueryCountContextHolder.clear();
	}

	@Test
	@DisplayName("Hibernate statement와 JDBC batch 통계를 분리해 성공 스냅샷을 만든다")
	void recordsSuccessfulOperationWithSeparateHibernateAndJdbcStatistics() {
		List<BulkOperationSnapshot> recordedSnapshots = new ArrayList<>();
		BulkOperationMonitor monitor = new BulkOperationMonitor(
			ticker(100L, 350L),
			snapshot -> {
				assertThat(BulkOperationContextHolder.getContext()).isNull();
				recordedSnapshots.add(snapshot);
			}
		);

		BulkOperationSnapshot snapshot = monitor.monitor("wishlist-delete-before", () -> {
			BulkOperationContext context = BulkOperationContextHolder.getContext();
			assertThat(context).isNotNull();

			context.recordHibernateStatement(SqlQueryType.SELECT);
			context.recordHibernateStatement(SqlQueryType.DELETE);
			context.recordJdbcBatch(2, 100, 2L);
			context.recordJdbcBatch(1, 100, 1L);
		});

		assertThat(snapshot.operationName()).isEqualTo("wishlist-delete-before");
		assertThat(snapshot.outcome()).isEqualTo(BulkOperationSnapshot.Outcome.SUCCESS);
		assertThat(snapshot.executionTimeNanos()).isEqualTo(250L);
		assertThat(snapshot.hibernateStatementCount(SqlQueryType.SELECT)).isEqualTo(1);
		assertThat(snapshot.hibernateStatementCount(SqlQueryType.DELETE)).isEqualTo(1);
		assertThat(snapshot.hibernateStatementCount(SqlQueryType.TOTAL)).isEqualTo(2);
		assertThat(snapshot.jdbcBatchCalls()).isEqualTo(2);
		assertThat(snapshot.jdbcSubmittedRows()).isEqualTo(3L);
		assertThat(snapshot.jdbcConfiguredBatchSize()).isEqualTo(100);
		assertThat(snapshot.jdbcAffectedRows()).isEqualTo(3L);
		assertThatThrownBy(() -> snapshot.hibernateStatementsByType().put(SqlQueryType.INSERT, 1))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(recordedSnapshots).containsExactly(snapshot);
		assertThat(BulkOperationContextHolder.getContext()).isNull();
	}

	@Test
	@DisplayName("JDBC 영향 행 수를 알 수 없는 batch가 하나라도 있으면 nullable 통계를 유지한다")
	void keepsJdbcAffectedRowsUnknownWhenAnyBatchDoesNotReportIt() {
		BulkOperationMonitor monitor = new BulkOperationMonitor(ticker(10L, 20L), ignored -> {
		});

		BulkOperationSnapshot snapshot = monitor.monitor("reservation-history-after", () -> {
			BulkOperationContext context = BulkOperationContextHolder.getContext();
			context.recordJdbcBatch(100, 100, 100L);
			context.recordJdbcBatch(20, 100, null);
		});

		assertThat(snapshot.jdbcBatchCalls()).isEqualTo(2);
		assertThat(snapshot.jdbcSubmittedRows()).isEqualTo(120L);
		assertThat(snapshot.jdbcConfiguredBatchSize()).isEqualTo(100);
		assertThat(snapshot.jdbcAffectedRows()).isNull();
	}

	@Test
	@DisplayName("측정 대상 예외를 그대로 전달하면서 실패 스냅샷을 남기고 컨텍스트를 정리한다")
	void recordsFailureRethrowsOriginalExceptionAndClearsContext() {
		List<BulkOperationSnapshot> recordedSnapshots = new ArrayList<>();
		BulkOperationMonitor monitor = new BulkOperationMonitor(
			ticker(1_000L, 1_750L),
			recordedSnapshots::add
		);
		IllegalStateException failure = new IllegalStateException("operation failed");

		assertThatThrownBy(() -> monitor.monitor("wishlist-delete-before", () -> {
			BulkOperationContext context = BulkOperationContextHolder.getContext();
			context.recordHibernateStatement(SqlQueryType.DELETE);
			context.recordJdbcBatch(25, 100, 25L);
			throw failure;
		}))
			.isSameAs(failure);

		assertThat(recordedSnapshots).singleElement().satisfies(snapshot -> {
			assertThat(snapshot.outcome()).isEqualTo(BulkOperationSnapshot.Outcome.FAILURE);
			assertThat(snapshot.executionTimeNanos()).isEqualTo(750L);
			assertThat(snapshot.hibernateStatementCount(SqlQueryType.DELETE)).isEqualTo(1);
			assertThat(snapshot.jdbcBatchCalls()).isEqualTo(1);
			assertThat(snapshot.jdbcSubmittedRows()).isEqualTo(25L);
			assertThat(snapshot.jdbcAffectedRows()).isEqualTo(25L);
		});
		assertThat(BulkOperationContextHolder.getContext()).isNull();
	}

	@Test
	@DisplayName("중첩 측정을 거부하되 바깥 컨텍스트는 덮어쓰거나 제거하지 않는다")
	void rejectsNestedMonitoringWithoutReplacingOuterContext() {
		AtomicLong nanos = new AtomicLong();
		BulkOperationMonitor monitor = new BulkOperationMonitor(
			() -> nanos.addAndGet(100L),
			ignored -> {
			}
		);

		BulkOperationSnapshot snapshot = monitor.monitor("outer-operation", () -> {
			BulkOperationContext outerContext = BulkOperationContextHolder.getContext();

			assertThatThrownBy(() -> monitor.monitor("inner-operation", () -> {
			}))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already active");
			assertThat(BulkOperationContextHolder.getContext()).isSameAs(outerContext);
		});

		assertThat(snapshot.outcome()).isEqualTo(BulkOperationSnapshot.Outcome.SUCCESS);
		assertThat(BulkOperationContextHolder.getContext()).isNull();
	}

	@Test
	@DisplayName("JDBC batch가 없으면 관련 선택 필드는 값 없음으로 남는다")
	void leavesOptionalJdbcFieldsEmptyWhenJdbcBatchWasNotReported() {
		BulkOperationMonitor monitor = new BulkOperationMonitor(ticker(5L, 8L), ignored -> {
		});

		BulkOperationSnapshot snapshot = monitor.monitor("hibernate-only", () -> {
		});

		assertThat(snapshot.jdbcBatchCalls()).isZero();
		assertThat(snapshot.jdbcSubmittedRows()).isZero();
		assertThat(snapshot.jdbcConfiguredBatchSize()).isNull();
		assertThat(snapshot.jdbcAffectedRows()).isNull();
	}

	@Test
	@DisplayName("스냅샷 기록 실패는 성공한 벌크 연산 결과를 실패로 바꾸지 않는다")
	void snapshotRecorderFailureDoesNotFailTheOperation() {
		BulkOperationMonitor monitor = new BulkOperationMonitor(
			ticker(100L, 200L),
			ignored -> {
				throw new IllegalStateException("recorder failed");
			}
		);

		BulkOperationSnapshot snapshot = monitor.monitor("wishlist-delete-before", () -> {
		});

		assertThat(snapshot.outcome()).isEqualTo(BulkOperationSnapshot.Outcome.SUCCESS);
		assertThat(BulkOperationContextHolder.getContext()).isNull();
	}

	@Test
	@DisplayName("벌크 측정이 끝나도 같은 스레드의 HTTP 쿼리 컨텍스트는 유지한다")
	void keepsRequestQueryContextAfterBulkMonitoringCompletes() {
		QueryCountContext requestContext = new QueryCountContext("DELETE", "/api/v2/admin/benchmarks/bulk-write");
		QueryCountContextHolder.initContext(requestContext);
		SqlQueryStatementInspector inspector = new SqlQueryStatementInspector();
		BulkOperationMonitor monitor = new BulkOperationMonitor(ticker(100L, 200L), ignored -> {
		});

		BulkOperationSnapshot bulkSnapshot = monitor.monitor(
			"wishlist-delete-before",
			() -> inspector.inspect("delete from wishlist_accommodation where wishlist_id = ?")
		);

		assertThat(QueryCountContextHolder.getContext()).isSameAs(requestContext);
		QueryCountSnapshot requestSnapshot = requestContext.snapshot();
		assertThat(requestSnapshot.countOf(SqlQueryType.DELETE)).isEqualTo(1);
		assertThat(bulkSnapshot.hibernateStatementCount(SqlQueryType.DELETE)).isEqualTo(1);
	}

	@Test
	@DisplayName("측정 대상과 기록기가 모두 실패해도 측정 대상의 원래 예외를 전달한다")
	void rethrowsOperationFailureWhenSnapshotRecorderAlsoFails() {
		IllegalArgumentException operationFailure = new IllegalArgumentException("operation failed");
		BulkOperationMonitor monitor = new BulkOperationMonitor(
			ticker(100L, 200L),
			ignored -> {
				throw new IllegalStateException("recorder failed");
			}
		);

		assertThatThrownBy(() -> monitor.monitor("wishlist-delete-before", () -> {
			throw operationFailure;
		}))
			.isSameAs(operationFailure);
		assertThat(BulkOperationContextHolder.getContext()).isNull();
	}

	private LongSupplier ticker(long... values) {
		Deque<Long> ticks = new ArrayDeque<>();
		for (long value : values) {
			ticks.addLast(value);
		}
		return ticks::removeFirst;
	}
}
