package kr.kro.airbob.common.benchmark.bulkwrite;

import java.util.EnumMap;
import java.util.Map;

import kr.kro.airbob.common.monitoring.SqlQueryType;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;

public record BulkWriteBenchmarkResult(
	String operationName,
	BulkOperationSnapshot.Outcome outcome,
	long serverOperationNanos,
	double serverOperationMs,
	Map<SqlQueryType, Integer> hibernateStatementsByType,
	int jdbcBatchCalls,
	long jdbcSubmittedRows,
	Integer jdbcConfiguredBatchSize,
	Long jdbcAffectedRows
) {

	public BulkWriteBenchmarkResult {
		hibernateStatementsByType = Map.copyOf(hibernateStatementsByType);
	}

	public static BulkWriteBenchmarkResult from(BulkOperationSnapshot snapshot) {
		EnumMap<SqlQueryType, Integer> statementCounts = new EnumMap<>(SqlQueryType.class);
		for (SqlQueryType queryType : SqlQueryType.values()) {
			statementCounts.put(queryType, snapshot.hibernateStatementCount(queryType));
		}

		return new BulkWriteBenchmarkResult(
			snapshot.operationName(),
			snapshot.outcome(),
			snapshot.executionTimeNanos(),
			snapshot.executionTimeMillis(),
			statementCounts,
			snapshot.jdbcBatchCalls(),
			snapshot.jdbcSubmittedRows(),
			snapshot.jdbcConfiguredBatchSize(),
			snapshot.jdbcAffectedRows()
		);
	}
}
