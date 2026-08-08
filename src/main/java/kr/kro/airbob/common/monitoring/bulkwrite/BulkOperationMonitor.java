package kr.kro.airbob.common.monitoring.bulkwrite;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BulkOperationMonitor {

	private final LongSupplier nanoTime;
	private final Consumer<BulkOperationSnapshot> snapshotRecorder;

	public BulkOperationMonitor() {
		this(System::nanoTime, BulkOperationMonitor::logSnapshot);
	}

	BulkOperationMonitor(LongSupplier nanoTime, Consumer<BulkOperationSnapshot> snapshotRecorder) {
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
		this.snapshotRecorder = Objects.requireNonNull(snapshotRecorder, "snapshotRecorder must not be null");
	}

	public BulkOperationSnapshot monitor(String operationName, Runnable operation) {
		Objects.requireNonNull(operation, "operation must not be null");
		BulkOperationContext context = new BulkOperationContext(operationName);

		BulkOperationContextHolder.initContext(context);
		try {
			long startedAtNanos = nanoTime.getAsLong();
			try {
				operation.run();
			} catch (RuntimeException | Error failure) {
				completeAndPublish(
					context,
					BulkOperationSnapshot.Outcome.FAILURE,
					startedAtNanos
				);
				throw failure;
			}

			return completeAndPublish(
				context,
				BulkOperationSnapshot.Outcome.SUCCESS,
				startedAtNanos
			);
		} finally {
			BulkOperationContextHolder.clear();
		}
	}

	private BulkOperationSnapshot completeAndPublish(
		BulkOperationContext context,
		BulkOperationSnapshot.Outcome outcome,
		long startedAtNanos
	) {
		long executionTimeNanos = nanoTime.getAsLong() - startedAtNanos;
		BulkOperationSnapshot snapshot = context.snapshot(outcome, executionTimeNanos);
		BulkOperationContextHolder.clear();
		publishSafely(snapshot);
		return snapshot;
	}

	private void publishSafely(BulkOperationSnapshot snapshot) {
		try {
			snapshotRecorder.accept(snapshot);
		} catch (RuntimeException recordingFailure) {
			log.warn("벌크 연산 모니터링 결과 기록에 실패했습니다. operation_name={}", snapshot.operationName(),
				recordingFailure);
		}
	}

	private static void logSnapshot(BulkOperationSnapshot snapshot) {
		log.info(
			"bulk_operation_report operation_name={} outcome={} execution_time_ms={} execution_time_ns={} "
				+ "hibernate_statements={} jdbc_batch_calls={} jdbc_submitted_rows={} "
				+ "jdbc_configured_batch_size={} jdbc_affected_rows={}",
			snapshot.operationName(),
			snapshot.outcome(),
			snapshot.executionTimeMillis(),
			snapshot.executionTimeNanos(),
			snapshot.hibernateStatementsByType(),
			snapshot.jdbcBatchCalls(),
			snapshot.jdbcSubmittedRows(),
			snapshot.jdbcConfiguredBatchSize(),
			snapshot.jdbcAffectedRows()
		);
	}
}
