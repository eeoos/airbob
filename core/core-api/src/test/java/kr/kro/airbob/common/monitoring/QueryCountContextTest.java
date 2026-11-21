package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("요청별 쿼리 카운트 컨텍스트 테스트")
class QueryCountContextTest {

	@AfterEach
	void tearDown() {
		QueryCountContextHolder.clear();
	}

	@Test
	@DisplayName("SQL 타입별 실행 횟수와 전체 실행 횟수를 누적한다")
	void incrementsQueryCountsByTypeAndTotal() {
		QueryCountContext context = new QueryCountContext("GET", "/api/v1/accommodations/{accommodationId}");

		context.incrementQueryCount("select * from accommodation where id = ?");
		context.incrementQueryCount("select * from review where accommodation_id = ?");
		context.incrementQueryCount("update accommodation set name = ?");

		QueryCountSnapshot snapshot = context.snapshot();
		assertThat(snapshot.httpMethod()).isEqualTo("GET");
		assertThat(snapshot.bestMatchPath()).isEqualTo("/api/v1/accommodations/{accommodationId}");
		assertThat(snapshot.countOf(SqlQueryType.SELECT)).isEqualTo(2);
		assertThat(snapshot.countOf(SqlQueryType.UPDATE)).isEqualTo(1);
		assertThat(snapshot.countOf(SqlQueryType.INSERT)).isZero();
		assertThat(snapshot.countOf(SqlQueryType.TOTAL)).isEqualTo(3);
	}

	@Test
	@DisplayName("컨텍스트를 다시 초기화하면 이전 컨텍스트를 교체한다")
	void replacesExistingContextOnSameThread() {
		QueryCountContext first = new QueryCountContext("GET", "/api/v1/old");
		QueryCountContext second = new QueryCountContext("POST", "/api/v1/new");

		QueryCountContextHolder.initContext(first);
		QueryCountContextHolder.initContext(second);

		assertThat(QueryCountContextHolder.getContext()).isSameAs(second);
	}

	@Test
	@DisplayName("clear는 현재 스레드의 컨텍스트를 제거한다")
	void clearsCurrentThreadContext() {
		QueryCountContextHolder.initContext(new QueryCountContext("GET", "/api/v1/accommodations"));

		QueryCountContextHolder.clear();

		assertThat(QueryCountContextHolder.getContext()).isNull();
	}

	@Test
	@DisplayName("스냅샷의 카운트 맵은 외부 변경으로부터 보호된다")
	void snapshotCountsAreImmutable() {
		QueryCountContext context = new QueryCountContext("GET", "/api/v1/accommodations");
		context.incrementQueryCount("select * from accommodation");

		QueryCountSnapshot snapshot = context.snapshot();

		assertThat(snapshot.queryCountByType())
			.containsEntry(SqlQueryType.SELECT, 1)
			.containsEntry(SqlQueryType.TOTAL, 1);
		assertThat(snapshot.queryCountByType()).doesNotContainKey(SqlQueryType.INSERT);
	}
}
