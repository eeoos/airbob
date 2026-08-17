package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AwsAppBundleConfigurationTest {

	private static final Path COMPOSE_FILE = Path.of(
		"infra", "aws", "bundles", "app", "compose.yml");

	@Test
	void awsAppBundleRunsAResourceBoundAppAndOneHostNodeExporter() throws IOException {
		assertThat(COMPOSE_FILE).exists();

		Map<String, Object> compose = yaml(COMPOSE_FILE);
		Map<String, Object> services = map(compose.get("services"));
		assertThat(services.keySet()).containsExactlyInAnyOrder("app", "node-exporter");

		Map<String, Object> app = map(services.get("app"));
		assertThat(app)
			.containsEntry("image", "${APP_IMAGE:?APP_IMAGE is required}")
			.containsEntry("platform", "linux/amd64")
			.containsEntry("cpus", 2.0)
			.containsEntry("mem_limit", "3G")
			.containsEntry("memswap_limit", "3G")
			.containsEntry("restart", "unless-stopped");
		assertThat(list(app.get("env_file")))
			.containsExactly(Map.of(
				"path", "${APP_ENV_FILE:?APP_ENV_FILE is required}",
				"required", true,
				"format", "raw"));
		assertThat(map(app.get("environment")))
			.containsExactly(Map.entry("JAVA_OPTS", "-Xms1536m -Xmx1536m -XX:+UseG1GC"));
		assertThat(list(app.get("ports"))).containsExactly("8080:8080");
		assertThat(map(app.get("healthcheck")))
			.containsEntry("test", List.of(
				"CMD", "wget", "-q", "--spider",
				"http://localhost:8080/actuator/health"))
			.containsEntry("interval", "30s")
			.containsEntry("timeout", "10s")
			.containsEntry("retries", 5)
			.containsEntry("start_period", "90s");

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

		assertThat(services.values())
			.extracting(service -> map(service).get("image"))
			.containsExactlyInAnyOrder(
				"${APP_IMAGE:?APP_IMAGE is required}",
				"${NODE_EXPORTER_IMAGE:?NODE_EXPORTER_IMAGE is required}");
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
