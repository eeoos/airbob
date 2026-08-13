package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

class RedisMonitoringConfigurationTest {

	private static final String EXPORTER_IMAGE = "oliver006/redis_exporter:v1.89.0";

	@Test
	void localComposeSeparatesGeneralAndCacheRedisWithDedicatedExporters() throws IOException {
		Map<String, Object> services = services("docker-compose.yml");

		assertRedisServices(services, true);
	}

	@Test
	void ociComposeConnectsTheApplicationToTheDedicatedCacheRedis() throws IOException {
		Map<String, Object> services = services("docker-compose.oci.yml");
		Map<String, Object> app = map(services.get("app"));

		assertRedisServices(services, false);
		assertThat(list(app.get("environment")))
			.contains("ACCOMMODATION_DETAIL_CACHE_REDIS_HOST=redis-cache");
		assertThat(map(app.get("depends_on"))).doesNotContainKey("redis-cache");
	}

	@Test
	void prometheusScrapesBothRedisExportersWithDashboardLabels() throws IOException {
		for (String fileName : new String[] {"prometheus.local.yml", "prometheus.oci.yml"}) {
			Map<String, Object> prometheus = yaml(
				Path.of("monitoring", "prometheus", fileName));
			List<Map<String, Object>> jobs = list(prometheus.get("scrape_configs"));

			assertRedisJob(jobs, "redis-general", "redis-exporter-general:9121", "general");
			assertRedisJob(jobs, "redis-cache", "redis-exporter-cache:9121", "cache");
		}
	}

	private void assertRedisServices(Map<String, Object> services, boolean local) {
		Map<String, Object> general = map(services.get("redis"));
		Map<String, Object> cache = map(services.get("redis-cache"));
		Map<String, Object> generalExporter = map(services.get("redis-exporter-general"));
		Map<String, Object> cacheExporter = map(services.get("redis-exporter-cache"));

		assertThat(general.get("command").toString())
			.contains("--appendonly yes", "--maxmemory-policy noeviction");
		assertThat(cache.get("command").toString())
			.contains("--appendonly no", "--maxmemory-policy allkeys-lru");
		if (local) {
			assertThat(list(cache.get("ports"))).contains("127.0.0.1:6380:6379");
		}
		assertExporter(generalExporter, "redis://redis:6379");
		assertExporter(cacheExporter, "redis://redis-cache:6379");
		assertThat(generalExporter).doesNotContainKey("depends_on");
		assertThat(cacheExporter).doesNotContainKey("depends_on");
		assertThat(map(map(services.get("prometheus")).get("depends_on")))
			.extractingByKeys("redis-exporter-general", "redis-exporter-cache")
			.allSatisfy(dependency -> assertThat(map(dependency))
				.containsEntry("condition", "service_started"));
	}

	private void assertExporter(Map<String, Object> exporter, String redisAddress) {
		assertThat(exporter.get("image")).isEqualTo(EXPORTER_IMAGE);
		assertThat(map(exporter.get("environment")))
			.containsEntry("REDIS_ADDR", redisAddress);
	}

	private void assertRedisJob(
		List<Map<String, Object>> jobs,
		String jobName,
		String target,
		String namespace
	) {
		Map<String, Object> job = jobs.stream()
			.filter(candidate -> jobName.equals(candidate.get("job_name")))
			.findFirst()
			.orElseThrow();
		Map<String, Object> staticConfig = map(list(job.get("static_configs")).getFirst());
		Map<String, Object> labels = map(staticConfig.get("labels"));

		assertThat(list(staticConfig.get("targets"))).containsExactly(target);
		assertThat(labels)
			.containsEntry("namespace", namespace)
			.containsEntry("instance", jobName);
	}

	private Map<String, Object> services(String fileName) throws IOException {
		return map(yaml(Path.of(fileName)).get("services"));
	}

	private Map<String, Object> yaml(Path path) throws IOException {
		try (var reader = java.nio.file.Files.newBufferedReader(path)) {
			return new Yaml().load(reader);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> List<T> list(Object value) {
		return (List<T>)value;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return (Map<String, Object>)value;
	}

	@Test
	void applicationProfilesPointTheCacheClientAtTheDedicatedRedis() throws IOException {
		assertProfileCacheEndpoint("application-oci.yaml",
			"${ACCOMMODATION_DETAIL_CACHE_REDIS_HOST:${REDIS_HOST}}",
			"${ACCOMMODATION_DETAIL_CACHE_REDIS_PORT:6379}");
		assertProfileCacheEndpoint("application-aws.yaml",
			"${ACCOMMODATION_DETAIL_CACHE_REDIS_HOST:${REDIS_HOST}}",
			"${ACCOMMODATION_DETAIL_CACHE_REDIS_PORT:6379}");
	}

	private void assertProfileCacheEndpoint(String resourceName, Object host, Object port)
		throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
			resourceName,
			new ClassPathResource(resourceName));

		assertThat(sources)
			.extracting(source -> source.getProperty("accommodation.detail-cache.redis.host"))
			.contains(host);
		assertThat(sources)
			.extracting(source -> source.getProperty("accommodation.detail-cache.redis.port"))
			.contains(port);
	}
}
