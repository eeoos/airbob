package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SQL 쿼리 타입 분류 테스트")
class SqlQueryTypeTest {

	@Test
	@DisplayName("기본 DML 쿼리를 타입별로 분류한다")
	void classifyDmlStatements() {
		assertThat(SqlQueryType.from("select * from accommodation")).isEqualTo(SqlQueryType.SELECT);
		assertThat(SqlQueryType.from(" INSERT INTO accommodation(id) VALUES (1)")).isEqualTo(SqlQueryType.INSERT);
		assertThat(SqlQueryType.from("update accommodation set name = ?")).isEqualTo(SqlQueryType.UPDATE);
		assertThat(SqlQueryType.from("delete from accommodation where id = ?")).isEqualTo(SqlQueryType.DELETE);
	}

	@Test
	@DisplayName("공백과 선행 주석을 제거한 뒤 실행 SQL 타입을 분류한다")
	void classifyStatementsAfterLeadingComments() {
		assertThat(SqlQueryType.from("/* Hibernate */ select a.id from accommodation a")).isEqualTo(SqlQueryType.SELECT);
		assertThat(SqlQueryType.from("-- generated query\nselect a.id from accommodation a")).isEqualTo(SqlQueryType.SELECT);
	}

	@Test
	@DisplayName("CTE로 시작하는 조회 쿼리는 SELECT로 분류한다")
	void classifyCteSelectStatement() {
		String sql = "with available as (select id from accommodation) select * from available";

		assertThat(SqlQueryType.from(sql)).isEqualTo(SqlQueryType.SELECT);
	}

	@Test
	@DisplayName("알 수 없거나 비어 있는 SQL은 OTHER로 분류한다")
	void classifyUnknownStatementsAsOther() {
		assertThat(SqlQueryType.from(null)).isEqualTo(SqlQueryType.OTHER);
		assertThat(SqlQueryType.from(" ")).isEqualTo(SqlQueryType.OTHER);
		assertThat(SqlQueryType.from("call refresh_stats()")).isEqualTo(SqlQueryType.OTHER);
		assertThat(SqlQueryType.from("truncate table accommodation")).isEqualTo(SqlQueryType.OTHER);
	}

	@Test
	@DisplayName("단어 경계를 확인해 SELECTIVE 같은 문자열을 SELECT로 오분류하지 않는다")
	void avoidPrefixOnlyClassification() {
		assertThat(SqlQueryType.from("selective value")).isEqualTo(SqlQueryType.OTHER);
	}
}
