package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SQL 쿼리 StatementInspector 테스트")
class SqlQueryStatementInspectorTest {

	private final SqlQueryStatementInspector inspector = new SqlQueryStatementInspector();

	@AfterEach
	void tearDown() {
		QueryCountContextHolder.clear();
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
	}
}
