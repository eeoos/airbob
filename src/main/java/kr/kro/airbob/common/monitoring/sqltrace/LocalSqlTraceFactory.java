package kr.kro.airbob.common.monitoring.sqltrace;

import com.p6spy.engine.event.JdbcEventListener;
import com.p6spy.engine.logging.P6LogFactory;

/** P6Spy가 생성하는 로컬 조회 전용 listener. 전체 JDBC 로그는 활성화하지 않는다. */
public final class LocalSqlTraceFactory extends P6LogFactory {

	@Override
	public JdbcEventListener getJdbcEventListener() {
		return new LocalReadSqlTraceListener();
	}
}
