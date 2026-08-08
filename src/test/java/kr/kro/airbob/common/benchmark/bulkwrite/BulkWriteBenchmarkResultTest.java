package kr.kro.airbob.common.benchmark.bulkwrite;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.common.monitoring.SqlQueryType;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;

@DisplayName("벌크 쓰기 벤치마크 결과 테스트")
class BulkWriteBenchmarkResultTest {

	@Test
	@DisplayName("측정 snapshot을 모든 SQL 유형의 0값을 포함한 응답으로 변환한다")
	void includesZeroStatementCounts() {
		BulkOperationSnapshot snapshot = new BulkOperationSnapshot(
			"wishlist-delete-before",
			BulkOperationSnapshot.Outcome.SUCCESS,
			1_500_000,
			Map.of(SqlQueryType.SELECT, 2, SqlQueryType.TOTAL, 2),
			0,
			0,
			null,
			null
		);

		BulkWriteBenchmarkResult result = BulkWriteBenchmarkResult.from(snapshot);

		assertThat(result.serverOperationMs()).isEqualTo(1.5);
		assertThat(result.hibernateStatementsByType())
			.containsEntry(SqlQueryType.SELECT, 2)
			.containsEntry(SqlQueryType.INSERT, 0)
			.containsEntry(SqlQueryType.UPDATE, 0)
			.containsEntry(SqlQueryType.DELETE, 0)
			.containsEntry(SqlQueryType.OTHER, 0)
			.containsEntry(SqlQueryType.TOTAL, 2);
		assertThat(result.jdbcBatchCalls()).isZero();
		assertThat(result.jdbcSubmittedRows()).isZero();
		assertThat(result.jdbcConfiguredBatchSize()).isNull();
		assertThat(result.jdbcAffectedRows()).isNull();
	}
}
