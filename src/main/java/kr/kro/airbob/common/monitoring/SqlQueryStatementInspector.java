package kr.kro.airbob.common.monitoring;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.stereotype.Component;

@Component
public class SqlQueryStatementInspector implements StatementInspector {

	@Override
	public String inspect(String sql) {
		QueryCountContext context = QueryCountContextHolder.getContext();
		if (context == null) {
			return sql;
		}

		SqlQueryType queryType = SqlQueryType.from(sql);
		context.incrementQueryCount(queryType);
		return sql;
	}
}
