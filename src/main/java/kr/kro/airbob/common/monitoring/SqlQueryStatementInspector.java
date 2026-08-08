package kr.kro.airbob.common.monitoring;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.stereotype.Component;

import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationContext;
import kr.kro.airbob.common.monitoring.bulkwrite.BulkOperationContextHolder;

@Component
public class SqlQueryStatementInspector implements StatementInspector {

	@Override
	public String inspect(String sql) {
		QueryCountContext context = QueryCountContextHolder.getContext();
		BulkOperationContext bulkOperationContext = BulkOperationContextHolder.getContext();
		if (context == null && bulkOperationContext == null) {
			return sql;
		}

		SqlQueryType queryType = SqlQueryType.from(sql);
		if (context != null) {
			context.incrementQueryCount(queryType);
		}
		if (bulkOperationContext != null) {
			bulkOperationContext.recordHibernateStatement(queryType);
		}
		return sql;
	}
}
