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

	@Test
	void redisExporterDashboardIsVendoredForFileProvisioning() throws IOException {
		JsonNode dashboard = readDashboard("airbob-redis.json");

		assertThat(dashboard.path("title").asText())
			.isEqualTo("Redis Dashboard for Prometheus Redis Exporter 1.x");
		assertThat(dashboard.path("gnetId").asInt()).isEqualTo(763);
		assertThat(dashboard.path("description").asText())
			.contains("grafana.com/grafana/dashboards/763");
		assertThat(dashboard.has("__inputs")).isFalse();
		assertPrometheusDatasourcesUseProvisionedUid(dashboard);

		String expressions = dashboard.findValues("expr").toString();
		assertThat(expressions)
			.contains("redis_up")
			.contains("redis_memory_used_bytes")
			.contains("redis_commands_total")
			.contains("redis_keyspace_hits_total")
			.contains("redis_evicted_keys_total");

		List<String> variableQueries = dashboard.path("templating").findValuesAsText("query");
		assertThat(variableQueries)
			.contains("label_values(redis_up, namespace)")
			.contains("label_values(redis_up{namespace=~\"$namespace\"}, instance)");
	}

	@Test
	void accommodationDetailCacheDashboardContainsApplicationCacheMetrics() throws IOException {
		JsonNode dashboard = readDashboard("airbob-accommodation-detail-cache.json");

		assertThat(dashboard.path("title").asText())
			.isEqualTo("Airbob - Accommodation Detail Cache");
		assertThat(dashboard.path("uid").asText())
			.isEqualTo("airbob-accommodation-detail-cache");
		assertPrometheusDatasourcesUseProvisionedUid(dashboard);

		String expressions = dashboard.findValues("expr").toString();
		assertThat(expressions)
			.contains("accommodation_detail_cache_request_total")
			.contains("accommodation_detail_cache_lock_wait_duration_seconds_bucket")
			.contains("accommodation_detail_cache_load_duration_seconds_bucket")
			.contains("accommodation_detail_cache_redis_operation_total")
			.contains("accommodation_detail_cache_eviction_total")
			.contains("source=\\\"after_commit\\\"")
			.contains("source=\\\"outbox\\\"");

		assertThat(dashboard.findValuesAsText("title"))
			.contains(
				"DB offload ratio",
				"Redis hit ratio",
				"Request outcome RPS",
				"Lock wait p50 / p95 / p99",
				"DB load p50 / p95 / p99",
				"Eviction attempts by source",
				"DB load outcome RPS",
				"Redis operation failure ratio"
			);

		assertThat(dashboard.path("links").findValuesAsText("url"))
			.contains(
				"/d/airbob-redis?var-namespace=cache&var-instance=redis-cache",
				"/d/spring_boot_21",
				"/d/airbob-query-count");

		assertThat(dashboard.path("templating").findValuesAsText("query"))
			.contains("after_commit,outbox", "accommodation,image,review");
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
