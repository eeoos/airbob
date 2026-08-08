package kr.kro.airbob.common.monitoring.bulkwrite;

import java.util.Map;
import java.util.Objects;

import kr.kro.airbob.common.monitoring.SqlQueryType;

public record BulkOperationSnapshot(
	String operationName,
	Outcome outcome,
	long executionTimeNanos,
	Map<SqlQueryType, Integer> hibernateStatementsByType,
	int jdbcBatchCalls,
	long jdbcSubmittedRows,
	Integer jdbcConfiguredBatchSize,
	Long jdbcAffectedRows
) {

	public BulkOperationSnapshot {
		if (operationName == null || operationName.isBlank()) {
			throw new IllegalArgumentException("operationName must not be blank");
		}
		Objects.requireNonNull(outcome, "outcome must not be null");
		Objects.requireNonNull(hibernateStatementsByType, "hibernateStatementsByType must not be null");
		if (executionTimeNanos < 0) {
			throw new IllegalArgumentException("executionTimeNanos must not be negative");
		}
		if (jdbcBatchCalls < 0 || jdbcSubmittedRows < 0) {
			throw new IllegalArgumentException("JDBC batch statistics must not be negative");
		}
		if (jdbcConfiguredBatchSize != null && jdbcConfiguredBatchSize < 1) {
			throw new IllegalArgumentException("jdbcConfiguredBatchSize must be positive");
		}
		if (jdbcAffectedRows != null && jdbcAffectedRows < 0) {
			throw new IllegalArgumentException("jdbcAffectedRows must not be negative");
		}

		hibernateStatementsByType = Map.copyOf(hibernateStatementsByType);
	}

	public int hibernateStatementCount(SqlQueryType queryType) {
		return hibernateStatementsByType.getOrDefault(queryType, 0);
	}

	public double executionTimeMillis() {
		return executionTimeNanos / 1_000_000.0;
	}

	public enum Outcome {
		SUCCESS,
		FAILURE
	}
}
