package kr.kro.airbob.common.monitoring.bulkwrite;

import java.util.EnumMap;

import kr.kro.airbob.common.monitoring.SqlQueryType;

/**
 * 단일 스레드에서 실행되는 한 벌크 연산의 측정값을 수집한다.
 */
public final class BulkOperationContext {

	private final String operationName;
	private final EnumMap<SqlQueryType, Integer> hibernateStatementsByType = new EnumMap<>(SqlQueryType.class);

	private int jdbcBatchCalls;
	private long jdbcSubmittedRows;
	private Integer jdbcConfiguredBatchSize;
	private long knownJdbcAffectedRows;
	private boolean jdbcAffectedRowsKnown = true;

	public BulkOperationContext(String operationName) {
		if (operationName == null || operationName.isBlank()) {
			throw new IllegalArgumentException("operationName must not be blank");
		}
		this.operationName = operationName;
	}

	public void recordHibernateStatement(SqlQueryType queryType) {
		hibernateStatementsByType.merge(queryType, 1, Math::addExact);
		hibernateStatementsByType.merge(SqlQueryType.TOTAL, 1, Math::addExact);
	}

	/**
	 * JDBC writer가 executeBatch 한 번을 호출한 뒤 명시적으로 보고한다.
	 * affectedRows가 null이면 SUCCESS_NO_INFO 등으로 정확한 영향 행 수를 알 수 없다는 뜻이다.
	 */
	public void recordJdbcBatch(long submittedRows, int configuredBatchSize, Long affectedRows) {
		if (submittedRows < 1) {
			throw new IllegalArgumentException("submittedRows must be positive");
		}
		if (configuredBatchSize < 1) {
			throw new IllegalArgumentException("configuredBatchSize must be positive");
		}
		if (affectedRows != null && affectedRows < 0) {
			throw new IllegalArgumentException("affectedRows must not be negative");
		}
		if (jdbcConfiguredBatchSize != null && jdbcConfiguredBatchSize != configuredBatchSize) {
			throw new IllegalArgumentException("configuredBatchSize must remain consistent within one operation");
		}

		jdbcConfiguredBatchSize = configuredBatchSize;
		jdbcBatchCalls = Math.addExact(jdbcBatchCalls, 1);
		jdbcSubmittedRows = Math.addExact(jdbcSubmittedRows, submittedRows);
		if (affectedRows == null) {
			jdbcAffectedRowsKnown = false;
		} else if (jdbcAffectedRowsKnown) {
			knownJdbcAffectedRows = Math.addExact(knownJdbcAffectedRows, affectedRows);
		}
	}

	public BulkOperationSnapshot snapshot(BulkOperationSnapshot.Outcome outcome, long executionTimeNanos) {
		Long jdbcAffectedRows = jdbcBatchCalls == 0 || !jdbcAffectedRowsKnown
			? null
			: knownJdbcAffectedRows;

		return new BulkOperationSnapshot(
			operationName,
			outcome,
			executionTimeNanos,
			hibernateStatementsByType,
			jdbcBatchCalls,
			jdbcSubmittedRows,
			jdbcConfiguredBatchSize,
			jdbcAffectedRows
		);
	}
}
