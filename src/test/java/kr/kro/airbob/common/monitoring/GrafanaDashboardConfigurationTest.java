package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class GrafanaDashboardConfigurationTest {

	private static final Path DASHBOARD_DIRECTORY = Path.of("monitoring", "grafana", "dashboards");
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Test
	void couponIssuanceComparisonDashboardContainsTheBenchmarkMetrics() throws IOException {
		JsonNode dashboard = readDashboard("airbob-coupon-issuance.json");

		assertThat(dashboard.path("title").asText()).isEqualTo("Airbob - Coupon Issuance Comparison");
		assertThat(dashboard.path("uid").asText()).isEqualTo("airbob-coupon-issuance");
		assertPrometheusDatasourcesUseProvisionedUid(dashboard);

		String expressions = dashboard.findValues("expr").toString();
		assertThat(expressions)
			.contains("coupon_issue_duration_seconds_count")
			.contains("coupon_issue_duration_seconds_bucket")
			.contains("coupon_database_issue_duration_seconds_bucket")
			.contains("coupon_lock_wait_duration_seconds_bucket")
			.contains("coupon_lock_timeout_total")
			.contains("coupon_lua_duration_seconds_bucket")
			.contains("coupon_compensation_total")
			.contains("result=\\\"success\\\"");

		List<String> links = dashboard.path("links").findValuesAsText("url");
		assertThat(links)
			.contains("/d/spring_boot_21", "/d/airbob-query-count");
	}

	@Test
	void springBootStatisticsDashboardIsVendoredForFileProvisioning() throws IOException {
		JsonNode dashboard = readDashboard("airbob-spring-boot-statistics.json");

		assertThat(dashboard.path("title").asText()).isEqualTo("Spring Boot 3.x Statistics");
		assertThat(dashboard.path("uid").asText()).isEqualTo("spring_boot_21");
		assertThat(dashboard.path("description").asText())
			.contains("grafana.com/grafana/dashboards/19004");
		assertThat(dashboard.has("__inputs")).isFalse();
		assertPrometheusDatasourcesUseProvisionedUid(dashboard);

		List<String> titles = dashboard.findValuesAsText("title");
		assertThat(titles)
			.contains("Basic Statistics", "JVM Statistics - Memory", "JVM Statistics - GC",
				"Database Connection Pool HikariCP  Statistics", "HTTP Statistics", "Logback Statistics");
		assertThat(dashboard.findValues("expr")).hasSizeGreaterThanOrEqualTo(40);
	}

	private JsonNode readDashboard(String fileName) throws IOException {
		Path dashboardPath = DASHBOARD_DIRECTORY.resolve(fileName);
		assertThat(dashboardPath).isRegularFile();
		return OBJECT_MAPPER.readTree(dashboardPath.toFile());
	}

	private void assertPrometheusDatasourcesUseProvisionedUid(JsonNode dashboard) {
		List<JsonNode> prometheusDatasources = dashboard.findValues("datasource").stream()
			.filter(JsonNode::isObject)
			.filter(datasource -> "prometheus".equals(datasource.path("type").asText()))
			.toList();

		assertThat(prometheusDatasources).isNotEmpty();
		assertThat(prometheusDatasources)
			.allSatisfy(datasource -> assertThat(datasource.path("uid").asText()).isEqualTo("prometheus"));
	}
}
