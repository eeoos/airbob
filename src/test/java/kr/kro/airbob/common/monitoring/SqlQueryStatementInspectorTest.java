package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationContext;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationContextHolder;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationSnapshot;

@DisplayName("SQL 쿼리 StatementInspector 테스트")
class SqlQueryStatementInspectorTest {

	private final SqlQueryStatementInspector inspector = new SqlQueryStatementInspector();

	@AfterEach
	void tearDown() {
		QueryCountContextHolder.clear();
		BulkOperationContextHolder.clear();
	}

	@Test
	@DisplayName("요청 컨텍스트가 있으면 inspect 시점에 SQL 실행 횟수를 누적한다")
	void incrementsQueryCountWhenContextExists() {
		QueryCountContext context = new QueryCountContext("GET", "/api/v1/accommodations");
		QueryCountContextHolder.initContext(context);
		String sql = "select * from accommodation";

		String inspectedSql = inspector.inspect(sql);

		assertThat(inspectedSql).isSameAs(sql);
		QueryCountSnapshot snapshot = context.snapshot();
		assertThat(snapshot.countOf(SqlQueryType.SELECT)).isEqualTo(1);
		assertThat(snapshot.countOf(SqlQueryType.TOTAL)).isEqualTo(1);
	}

	@Test
	@DisplayName("요청 컨텍스트가 없으면 SQL을 그대로 반환하고 아무 작업도 하지 않는다")
	void returnsSqlWhenContextDoesNotExist() {
		String sql = "select * from accommodation";

		String inspectedSql = inspector.inspect(sql);

		assertThat(inspectedSql).isSameAs(sql);
		assertThat(QueryCountContextHolder.getContext()).isNull();
		assertThat(BulkOperationContextHolder.getContext()).isNull();
	}

	@Test
	@DisplayName("HTTP 요청과 벌크 컨텍스트가 함께 있으면 같은 SQL을 각각 한 번씩 누적한다")
	void incrementsRequestAndBulkContextsTogether() {
		QueryCountContext requestContext = new QueryCountContext("DELETE", "/api/v2/admin/benchmarks/bulk-write");
		BulkOperationContext bulkContext = new BulkOperationContext("wishlist-delete-before");
		QueryCountContextHolder.initContext(requestContext);
		BulkOperationContextHolder.initContext(bulkContext);
		String sql = "/* Hibernate */ delete from wishlist_accommodation where wishlist_id = ?";

		String inspectedSql = inspector.inspect(sql);

		assertThat(inspectedSql).isSameAs(sql);
		QueryCountSnapshot requestSnapshot = requestContext.snapshot();
		assertThat(requestSnapshot.countOf(SqlQueryType.DELETE)).isEqualTo(1);
		assertThat(requestSnapshot.countOf(SqlQueryType.TOTAL)).isEqualTo(1);

		BulkOperationSnapshot bulkSnapshot = bulkContext.snapshot(
			BulkOperationSnapshot.Outcome.SUCCESS,
			1L
		);
		assertThat(bulkSnapshot.hibernateStatementCount(SqlQueryType.DELETE)).isEqualTo(1);
		assertThat(bulkSnapshot.hibernateStatementCount(SqlQueryType.TOTAL)).isEqualTo(1);
	}
}
