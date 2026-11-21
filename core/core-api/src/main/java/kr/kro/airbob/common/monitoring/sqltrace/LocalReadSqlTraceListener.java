package kr.kro.airbob.common.monitoring.sqltrace;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import com.mysql.cj.PreparedQuery;
import com.mysql.cj.jdbc.JdbcPreparedStatement;
import com.p6spy.engine.common.StatementInformation;
import com.p6spy.engine.event.SimpleJdbcEventListener;

import jakarta.servlet.http.HttpServletRequest;
import kr.kro.airbob.common.monitoring.SqlQueryType;

final class LocalReadSqlTraceListener extends SimpleJdbcEventListener {

	static final String LOGGER_NAME = "airbob.sql.trace";
	private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

	@Override
	public void onAfterAnyExecute(StatementInformation information, long elapsedNanos, SQLException failure) {
		if (!log.isInfoEnabled() || failure != null
			|| !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
			return;
		}
		HttpServletRequest request = attributes.getRequest();
		if (!"GET".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/")
			|| SqlQueryType.from(information.getStatementQuery()) != SqlQueryType.SELECT) {
			return;
		}

		try {
			String sql = sqlWithValues(information).stripTrailing();
			String route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
				instanceof String pattern ? pattern : "/api/**";
			String millis = String.format(Locale.ROOT, "%.3f", elapsedNanos / 1_000_000.0);
			log.info("SQL_TRACE GET {} | JDBC execute {} ms\n{}{}", route, millis, sql,
				sql.endsWith(";") ? "" : ";");
		} catch (SQLException | RuntimeException exception) {
			// 진단 실패가 성공한 업무 SELECT의 결과를 바꾸지 않게 한다.
			log.debug("SQL_TRACE rendering unavailable ({})", exception.getClass().getSimpleName());
		}
	}

	private String sqlWithValues(StatementInformation information) throws SQLException {
		Statement statement = information.getStatement();
		if (statement != null && statement.isWrapperFor(JdbcPreparedStatement.class)) {
			JdbcPreparedStatement mysql = statement.unwrap(JdbcPreparedStatement.class);
			// 드라이버의 escaping, UUID binary, DATETIME(6) 표현을 그대로 사용한다.
			return ((PreparedQuery)mysql.getQuery()).asSql();
		}
		return information.getSqlWithValues();
	}
}
