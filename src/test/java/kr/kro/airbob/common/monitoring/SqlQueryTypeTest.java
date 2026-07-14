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
	@DisplayName("CTE 본문이 아니라 CTE 뒤의 최종 연산으로 쿼리 타입을 분류한다")
	void classifyCteStatementsByOuterOperation() {
		assertThat(SqlQueryType.from(
			"with available as (select id from accommodation) select * from available"
		)).isEqualTo(SqlQueryType.SELECT);
		assertThat(SqlQueryType.from(
			"with changed as (select id from accommodation) update accommodation set name = ? where id in (select id from changed)"
		)).isEqualTo(SqlQueryType.UPDATE);
		assertThat(SqlQueryType.from(
			"with obsolete as (select id from accommodation) delete from accommodation where id in (select id from obsolete)"
		)).isEqualTo(SqlQueryType.DELETE);
		assertThat(SqlQueryType.from(
			"with source as (select id from accommodation) insert into history(id) select id from source"
		)).isEqualTo(SqlQueryType.INSERT);
	}

	@Test
	@DisplayName("여러 CTE의 중첩 괄호, 주석, 문자열과 인용 식별자 속 키워드는 최종 연산으로 오인하지 않는다")
	void ignoresNestedAndQuotedKeywordsInsideMultipleCtes() {
		String sql = """
			/* Hibernate */ with recursive `select`(`value`) as (
			  select concat(') update ignored', "delete")
			  from accommodation
			  where id in (select id from accommodation /* ) insert */)
			), second_cte as (
			  select '(' as token -- ) delete ignored
			  from `select`
			)
			update accommodation set name = 'with select' where id in (select id from second_cte)
			""";

		assertThat(SqlQueryType.from(sql)).isEqualTo(SqlQueryType.UPDATE);
	}

	@Test
	@DisplayName("MySQL에서 공백 없는 이중 빼기는 주석이 아니므로 CTE 바깥 SELECT를 찾는다")
	void treatsDoubleMinusArithmeticWithoutWhitespaceAsSql() {
		String sql = "with balances as (select balance--1 as adjusted from account) select * from balances";

		assertThat(SqlQueryType.from(sql)).isEqualTo(SqlQueryType.SELECT);
	}

	@Test
	@DisplayName("MySQL에서 두 번째 dash 뒤 공백이 있는 경우에는 실제 줄 주석으로 처리한다")
	void treatsDoubleDashFollowedByWhitespaceAsComment() {
		String sql = """
			with balances as (
			  select balance -- subtract pending amount
			  from account
			)
			select * from balances
			""";

		assertThat(SqlQueryType.from(sql)).isEqualTo(SqlQueryType.SELECT);
	}

	@Test
	@DisplayName("최종 연산을 안전하게 찾을 수 없는 CTE는 OTHER로 분류한다")
	void classifyUnresolvedOrMalformedCteAsOther() {
		assertThat(SqlQueryType.from("with values_cte as (select id from accommodation) merge into archive"))
			.isEqualTo(SqlQueryType.OTHER);
		assertThat(SqlQueryType.from("with broken as (select id from accommodation update accommodation set name = ?"))
			.isEqualTo(SqlQueryType.OTHER);
		assertThat(SqlQueryType.from("with missing_body as update accommodation set name = ?"))
			.isEqualTo(SqlQueryType.OTHER);
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
