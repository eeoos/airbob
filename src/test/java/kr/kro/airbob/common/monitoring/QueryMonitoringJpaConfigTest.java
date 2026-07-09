package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;

@DisplayName("쿼리 모니터링 JPA 설정 테스트")
class QueryMonitoringJpaConfigTest {

	@Test
	@DisplayName("Spring이 관리하는 StatementInspector 인스턴스를 Hibernate 설정에 등록한다")
	void registersSpringManagedStatementInspector() {
		QueryMonitoringJpaConfig config = new QueryMonitoringJpaConfig();
		SqlQueryStatementInspector inspector = new SqlQueryStatementInspector();
		HibernatePropertiesCustomizer customizer = config.queryCountStatementInspectorCustomizer(inspector);
		Map<String, Object> hibernateProperties = new HashMap<>();

		customizer.customize(hibernateProperties);

		assertThat(hibernateProperties)
			.containsEntry(QueryMonitoringJpaConfig.STATEMENT_INSPECTOR_PROPERTY, inspector);
	}
}
