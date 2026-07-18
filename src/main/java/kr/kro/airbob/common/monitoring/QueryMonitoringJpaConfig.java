package kr.kro.airbob.common.monitoring;

import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class QueryMonitoringJpaConfig {

	public static final String STATEMENT_INSPECTOR_PROPERTY = "hibernate.session_factory.statement_inspector";

	@Bean
	HibernatePropertiesCustomizer queryCountStatementInspectorCustomizer(
		SqlQueryStatementInspector sqlQueryStatementInspector
	) {
		return hibernateProperties ->
			hibernateProperties.put(STATEMENT_INSPECTOR_PROPERTY, sqlQueryStatementInspector);
	}
}
