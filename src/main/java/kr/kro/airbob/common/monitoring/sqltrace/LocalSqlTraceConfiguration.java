package kr.kro.airbob.common.monitoring.sqltrace;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.p6spy.engine.spy.P6ModuleManager;
import com.p6spy.engine.spy.P6SpyDriver;
import com.p6spy.engine.spy.P6SpyFactory;
import com.zaxxer.hikari.HikariDataSource;

@Configuration(proxyBeanMethods = false)
@Profile("dev & sql-trace & !aws & !oci & !performance-lab")
@ConditionalOnClass(P6SpyDriver.class)
public class LocalSqlTraceConfiguration {

	@Bean
	static SqlTraceDriverPostProcessor sqlTraceDriverPostProcessor() {
		return new SqlTraceDriverPostProcessor();
	}

	static final class SqlTraceDriverPostProcessor implements BeanPostProcessor, DisposableBean {
		private static final String MODULE_PROPERTY = "p6spy.config.modulelist";
		private static final String MODULES = P6SpyFactory.class.getName() + ","
			+ LocalSqlTraceFactory.class.getName();
		private String previousModules;
		private boolean configured;

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			if (bean instanceof HikariDataSource hikari && hikari.getJdbcUrl() != null
				&& hikari.getJdbcUrl().startsWith("jdbc:mysql:")) {
				if (!configured) {
					previousModules = System.getProperty(MODULE_PROPERTY);
					System.setProperty(MODULE_PROPERTY, MODULES);
					P6ModuleManager.getInstance().reload();
					configured = true;
				}
				// Hikari 풀과 반환 Connection은 유지하고 내부 JDBC 드라이버만 계측한다.
				hikari.setJdbcUrl("jdbc:p6spy:" + hikari.getJdbcUrl().substring("jdbc:".length()));
				hikari.setDriverClassName(P6SpyDriver.class.getName());
			}
			return bean;
		}

		@Override
		public void destroy() {
			if (configured && MODULES.equals(System.getProperty(MODULE_PROPERTY))) {
				if (previousModules == null) {
					System.clearProperty(MODULE_PROPERTY);
				} else {
					System.setProperty(MODULE_PROPERTY, previousModules);
				}
				P6ModuleManager.getInstance().reload();
			}
		}
	}
}
