package kr.kro.airbob.common.monitoring;

import java.util.EnumMap;
import java.util.Map;

public class QueryCountContext {

	private final String httpMethod;
	private final String bestMatchPath;
	private final EnumMap<SqlQueryType, Integer> queryCountByType = new EnumMap<>(SqlQueryType.class);

	public QueryCountContext(String httpMethod, String bestMatchPath) {
		this.httpMethod = httpMethod;
		this.bestMatchPath = bestMatchPath;
	}

	public void incrementQueryCount(String sql) {
		SqlQueryType queryType = SqlQueryType.from(sql);
		queryCountByType.merge(queryType, 1, Integer::sum);
		queryCountByType.merge(SqlQueryType.TOTAL, 1, Integer::sum);
	}

	public QueryCountSnapshot snapshot() {
		return new QueryCountSnapshot(httpMethod, bestMatchPath, Map.copyOf(queryCountByType));
	}
}
