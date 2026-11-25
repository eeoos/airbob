package kr.kro.airbob.core.support.monitoring;

import java.util.Map;

public record QueryCountSnapshot(
	String httpMethod,
	String bestMatchPath,
	Map<SqlQueryType, Integer> queryCountByType) {

	public QueryCountSnapshot {
		queryCountByType = Map.copyOf(queryCountByType);
	}

	public int countOf(SqlQueryType queryType) {
		return queryCountByType.getOrDefault(queryType, 0);
	}
}
