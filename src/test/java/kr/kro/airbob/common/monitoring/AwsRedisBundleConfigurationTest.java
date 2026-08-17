package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AwsRedisBundleConfigurationTest {

	private static final Path COMPOSE_FILE = Path.of(
		"infra", "aws", "bundles", "redis", "compose.yml");

	@Test
	void awsRedisBundleSeparatesDurableAndCacheRedisWithDedicatedExporters() throws IOException {
		assertThat(COMPOSE_FILE).exists();

		Map<String, Object> compose = yaml(COMPOSE_FILE);
		Map<String, Object> services = map(compose.get("services"));

		assertThat(services.keySet()).containsExactlyInAnyOrder(
			"redis", "redis-cache", "redis-exporter-general",
			"redis-exporter-cache", "node-exporter");
		assertThat(map(compose.get("volumes")).keySet()).containsExactly("redis-general-data");

		assertRedis(
			map(services.get("redis")),
			"redis-server --save \"\" --appendonly yes --appendfsync everysec "
				+ "--maxmemory 512mb --maxmemory-policy noeviction",
			"6379:6379", "redis-general-data:/data", "640M");
		assertRedis(
			map(services.get("redis-cache")),
			"redis-server --save \"\" --appendonly no "
				+ "--maxmemory 256mb --maxmemory-policy allkeys-lru",
			"6380:6379", null, "320M");

		assertExporter(
			map(services.get("redis-exporter-general")),
			"redis://redis:6379", "9121:9121");
		assertExporter(
			map(services.get("redis-exporter-cache")),
			"redis://redis-cache:6379", "9122:9121");

		Map<String, Object> nodeExporter = map(services.get("node-exporter"));
		assertThat(nodeExporter)
			.containsEntry("image", "${NODE_EXPORTER_IMAGE:?NODE_EXPORTER_IMAGE is required}")
			.containsEntry("platform", "linux/amd64")
			.containsEntry("pid", "host")
			.containsEntry("mem_limit", "128M")
			.containsEntry("memswap_limit", "128M")
			.containsEntry("restart", "unless-stopped");
		assertThat(list(nodeExporter.get("ports"))).containsExactly("9100:9100");
		assertThat(list(nodeExporter.get("volumes"))).containsExactly("/:/host:ro");
		assertThat(list(nodeExporter.get("command"))).containsExactly("--path.rootfs=/host");
	}

	private void assertRedis(
		Map<String, Object> redis,
		String command,
		String port,
		String volume,
		String memoryLimit
	) {
		assertThat(redis)
			.containsEntry("image", "${REDIS_IMAGE:?REDIS_IMAGE is required}")
			.containsEntry("platform", "linux/amd64")
			.containsEntry("command", command)
			.containsEntry("mem_limit", memoryLimit)
			.containsEntry("memswap_limit", memoryLimit)
			.containsEntry("restart", "unless-stopped");
		assertThat(list(redis.get("ports"))).containsExactly(port);
		assertThat(map(redis.get("healthcheck")).get("test"))
			.isEqualTo(List.of("CMD", "redis-cli", "ping"));
		if (volume == null) {
			assertThat(redis).doesNotContainKey("volumes");
			return;
		}
		assertThat(list(redis.get("volumes"))).containsExactly(volume);
	}

	private void assertExporter(Map<String, Object> exporter, String address, String port) {
		assertThat(exporter)
			.containsEntry("image", "${REDIS_EXPORTER_IMAGE:?REDIS_EXPORTER_IMAGE is required}")
			.containsEntry("platform", "linux/amd64")
			.containsEntry("mem_limit", "64M")
			.containsEntry("memswap_limit", "64M")
			.containsEntry("restart", "unless-stopped");
		assertThat(map(exporter.get("environment"))).containsEntry("REDIS_ADDR", address);
		assertThat(list(exporter.get("ports"))).containsExactly(port);
	}

	private Map<String, Object> yaml(Path path) throws IOException {
		try (var reader = Files.newBufferedReader(path)) {
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
}
