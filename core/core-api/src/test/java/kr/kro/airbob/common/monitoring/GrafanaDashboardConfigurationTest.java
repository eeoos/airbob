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
	void couponIssuanceDashboardContainsTheProductionMetrics() throws IOException {
		JsonNode dashboard = readDashboard("airbob-coupon-issuance.json");

		assertThat(dashboard.path("title").asText()).isEqualTo("Airbob - Coupon Issuance");
		assertThat(dashboard.path("uid").asText()).isEqualTo("airbob-coupon-issuance");
		assertPrometheusDatasourcesUseProvisionedUid(dashboard);

		String expressions = dashboard.findValues("expr").toString();
		assertThat(expressions)
			.contains("coupon_issue_duration_seconds_count")
			.contains("coupon_issue_duration_seconds_bucket")
			.contains("coupon_database_issue_duration_seconds_count")
			.contains("coupon_database_issue_duration_seconds_bucket")
			.contains("coupon_issue_retryable_total")
			.contains("result=\\\"success\\\"");

		assertThat(expressions).doesNotContain("coupon_lock_", "coupon_lua_", "coupon_compensation_");
		assertThat(dashboard.path("templating").path("list").findValuesAsText("name"))
			.doesNotContain("strategy");
		assertThat(expressions).doesNotContain("skip_composite", "skip_surrogate_rc");

		List<String> links = dashboard.path("links").findValuesAsText("url");
		assertThat(links)
			.contains("/d/spring_boot_21", "/d/airbob-query-count");
	}

	@Test
	void couponDashboardSeparatesFinalIssuancesFromAttemptsAndSharedPoolPressure() throws IOException {
		JsonNode dashboard = readDashboard("airbob-coupon-issuance.json");
		assertThat(panel(dashboard, "Successful issuance RPS").path("targets").get(0).path("expr").asText())
			.contains("coupon_issue_duration_seconds_count", "result=\"success\"");
		assertThat(panel(dashboard, "All-outcome issuance service RPS").path("targets").get(0)
			.path("expr").asText()).contains("coupon_issue_duration_seconds_count").doesNotContain("result=");
		assertThat(panel(dashboard, "DB attempt outcomes per second").path("targets").get(0)
			.path("expr").asText()).contains("coupon_database_issue_duration_seconds_count", "strategy, result");
		assertThat(panel(dashboard, "Retryable conditions per second").path("targets").get(0)
			.path("expr").asText()).contains("coupon_issue_retryable_total", "strategy, reason");

		for (JsonNode panel : dashboard.path("panels")) {
			for (JsonNode target : panel.path("targets")) {
				String expression = target.path("expr").asText();
				assertThat(expression).contains("environment=~\"$environment\"", "instance=~\"$instance\"");
				if (expression.contains("hikaricp_")) {
					assertThat(expression).contains("pool=~\"$pool\"").doesNotContain("strategy=");
				} else if (expression.contains("coupon_")) {
					assertThat(expression).contains("strategy=\"skip_surrogate_rr\"");
				}
			}
		}
	}

	@Test
	void couponDashboardPanelsDoNotOverlap() throws IOException {
		JsonNode panels = readDashboard("airbob-coupon-issuance.json").path("panels");
		for (int i = 0; i < panels.size(); i++) {
			JsonNode a = panels.get(i).path("gridPos");
			for (int j = i + 1; j < panels.size(); j++) {
				JsonNode b = panels.get(j).path("gridPos");
				boolean overlap = a.path("x").asInt() < b.path("x").asInt() + b.path("w").asInt()
					&& b.path("x").asInt() < a.path("x").asInt() + a.path("w").asInt()
					&& a.path("y").asInt() < b.path("y").asInt() + b.path("h").asInt()
					&& b.path("y").asInt() < a.path("y").asInt() + a.path("h").asInt();
				assertThat(overlap).as("%s overlaps %s", panels.get(i).path("title"), panels.get(j).path("title"))
					.isFalse();
			}
		}
	}

	private JsonNode panel(JsonNode dashboard, String title) {
		for (JsonNode panel : dashboard.path("panels")) {
			if (title.equals(panel.path("title").asText())) return panel;
		}
		throw new AssertionError("Missing dashboard panel: " + title);
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
