package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AwsMonitoringBundleConfigurationTest {

	private static final Path COMPOSE_FILE = Path.of(
		"infra", "aws", "bundles", "monitoring", "compose.yml");
	private static final Path PROMETHEUS_FILE = Path.of(
		"monitoring", "prometheus", "prometheus.aws.yml");
	private static final Path PROMETHEUS_DATASOURCE = Path.of(
		"monitoring", "grafana", "provisioning", "datasources", "prometheus.yml");
	private static final Path CLOUDWATCH_DATASOURCE = Path.of(
		"monitoring", "grafana", "provisioning", "datasources", "cloudwatch.aws.yml");
	private static final String SYNTHETIC_PASSWORD =
		"  ${TASK6_COLLISION} \"double quoted\" 'single quoted' # literal suffix  ";
	private static final String CANONICAL_SYNTHETIC_PASSWORD =
		"  $${TASK6_COLLISION} \"double quoted\" 'single quoted' # literal suffix  ";
	private static final List<String> STATIC_TARGETS = List.of(
		"redis-general.lab.airbob.internal:9121",
		"redis-cache.lab.airbob.internal:9122",
		"kafka.lab.airbob.internal:7071",
		"connect.lab.airbob.internal:9404",
		"elasticsearch.lab.airbob.internal:9114",
		"monitoring.lab.airbob.internal:9100");

	@Test
	void discoversOnlyRunningAppAndNonMonitoringNodeExporterInstances() throws IOException {
		assertThat(PROMETHEUS_FILE).exists();
		List<Map<String, Object>> jobs = list(yaml(PROMETHEUS_FILE).get("scrape_configs"));

		Map<String, Object> app = job(jobs, "airbob");
		assertThat(app).containsEntry("metrics_path", "/actuator/prometheus");
		assertEc2Discovery(app, 8080);
		assertThat(list(app.get("relabel_configs")))
			.contains(
				keep("__meta_ec2_tag_Project", "airbob"),
				keep("__meta_ec2_tag_Environment", "performance-lab"),
				keep("__meta_ec2_tag_Service", "app"),
				keep("__meta_ec2_instance_state", "running"),
				Map.of(
					"source_labels", List.of("__meta_ec2_tag_Environment"),
					"target_label", "environment"));

		Map<String, Object> nodeExporter = job(jobs, "node-exporter");
		assertEc2Discovery(nodeExporter, 9100);
		assertThat(list(nodeExporter.get("relabel_configs")))
			.contains(
				keep("__meta_ec2_tag_Project", "airbob"),
				keep("__meta_ec2_tag_Environment", "performance-lab"),
				keep("__meta_ec2_tag_Monitoring", "node-exporter"),
				keep("__meta_ec2_instance_state", "running"),
				Map.of(
					"source_labels", List.of("__meta_ec2_tag_Service"),
					"regex", "monitoring",
					"action", "drop"));
	}

	@Test
	void scrapesExactlyTheSixApprovedPrivateStaticTargets() throws IOException {
		List<Map<String, Object>> jobs = list(yaml(PROMETHEUS_FILE).get("scrape_configs"));

		List<String> targets = jobs.stream()
			.flatMap(candidate -> AwsMonitoringBundleConfigurationTest
				.<Map<String, Object>>list(candidate.get("static_configs")).stream())
			.flatMap(staticConfig -> AwsMonitoringBundleConfigurationTest
				.<String>list(staticConfig.get("targets")).stream())
			.toList();

		assertThat(targets).containsExactlyInAnyOrderElementsOf(STATIC_TARGETS);
		assertThat(targets).filteredOn("monitoring.lab.airbob.internal:9100"::equals).hasSize(1);
	}

	@Test
	void provisionsDefaultCredentialCloudWatchAlongsideTheStablePrometheusUid()
		throws IOException {
		Map<String, Object> prometheus = datasource(PROMETHEUS_DATASOURCE);
		assertThat(prometheus)
			.containsEntry("name", "Prometheus")
			.containsEntry("uid", "prometheus")
			.containsEntry("type", "prometheus")
			.containsEntry("url", "http://prometheus:9090");

		Map<String, Object> cloudWatch = datasource(CLOUDWATCH_DATASOURCE);
		assertThat(cloudWatch)
			.containsEntry("name", "CloudWatch")
			.containsEntry("uid", "cloudwatch")
			.containsEntry("type", "cloudwatch")
			.containsEntry("access", "proxy");
		assertThat(map(cloudWatch.get("jsonData"))).containsExactlyInAnyOrderEntriesOf(Map.of(
			"authType", "default",
			"defaultRegion", "ap-northeast-2"));
		assertThat(Files.readString(CLOUDWATCH_DATASOURCE))
			.doesNotContain("accessKey", "secretKey", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY");
	}

	@Test
	void monitoringBundleKeepsManagementUisOnLoopbackAndMetricsInTheVpc()
		throws IOException {
		assertThat(COMPOSE_FILE).exists();
		Map<String, Object> compose = yaml(COMPOSE_FILE);
		assertThat(compose.keySet()).containsExactlyInAnyOrder("services", "volumes");

		Map<String, Object> services = map(compose.get("services"));
		assertThat(services.keySet()).containsExactlyInAnyOrder(
			"prometheus", "grafana", "node-exporter");
		assertThat(map(compose.get("volumes")).keySet())
			.containsExactlyInAnyOrder("prometheus-data", "grafana-data");

		Map<String, Object> prometheus = map(services.get("prometheus"));
		assertService(prometheus,
			"${PROMETHEUS_IMAGE:?PROMETHEUS_IMAGE is required}", "512M");
		assertThat(list(prometheus.get("ports"))).containsExactly("127.0.0.1:9090:9090");
		assertThat(list(prometheus.get("volumes"))).containsExactlyInAnyOrder(
			"../../../../monitoring/prometheus/prometheus.aws.yml:/etc/prometheus/prometheus.yml:ro",
			"prometheus-data:/prometheus");
		assertThat(list(prometheus.get("command"))).containsExactly(
			"--config.file=/etc/prometheus/prometheus.yml",
			"--storage.tsdb.path=/prometheus",
			"--storage.tsdb.retention.time=15d",
			"--storage.tsdb.retention.size=5GB");

		Map<String, Object> grafana = map(services.get("grafana"));
		assertService(grafana, "${GRAFANA_IMAGE:?GRAFANA_IMAGE is required}", "384M");
		assertThat(list(grafana.get("ports"))).containsExactly("127.0.0.1:3000:3000");
		assertThat(list(grafana.get("env_file"))).containsExactly(Map.of(
			"path", "${MONITORING_ENV_FILE:?MONITORING_ENV_FILE is required}",
			"required", true,
			"format", "raw"));
		List<String> entrypoint = list(grafana.get("entrypoint"));
		assertThat(entrypoint).hasSize(3);
		assertThat(entrypoint.subList(0, 2)).containsExactly("/bin/sh", "-ec");
		assertThat(entrypoint.get(2))
			.contains("GRAFANA_ADMIN_PASSWORD", "export GF_SECURITY_ADMIN_PASSWORD", "exec /run.sh");
		assertThat(map(grafana.get("environment"))).containsExactlyInAnyOrderEntriesOf(Map.of(
			"GF_SECURITY_ADMIN_USER", "admin",
			"GF_USERS_ALLOW_SIGN_UP", "false",
			"GF_AUTH_ANONYMOUS_ENABLED", "false",
			"GF_PLUGINS_PREINSTALL_DISABLED", "true",
			"GF_PLUGINS_PREINSTALL_AUTO_UPDATE", "false",
			"GRAFANA_DASHBOARD_ALLOW_UI_UPDATES", "false"));
		assertThat(list(grafana.get("volumes"))).containsExactlyInAnyOrder(
			"grafana-data:/var/lib/grafana",
			"../../../../monitoring/grafana/provisioning/datasources/prometheus.yml:/etc/grafana/provisioning/datasources/prometheus.yml:ro",
			"../../../../monitoring/grafana/provisioning/datasources/cloudwatch.aws.yml:/etc/grafana/provisioning/datasources/cloudwatch.yml:ro",
			"../../../../monitoring/grafana/provisioning/dashboards/airbob.yml:/etc/grafana/provisioning/dashboards/airbob.yml:ro",
			"../../../../monitoring/grafana/dashboards:/etc/grafana/dashboards:ro");

		Map<String, Object> nodeExporter = map(services.get("node-exporter"));
		assertService(nodeExporter,
			"${NODE_EXPORTER_IMAGE:?NODE_EXPORTER_IMAGE is required}", "128M");
		assertThat(nodeExporter).containsEntry("pid", "host");
		assertThat(list(nodeExporter.get("ports"))).containsExactly("9100:9100");
		assertThat(list(nodeExporter.get("volumes"))).containsExactly("/:/host:ro");
		assertThat(list(nodeExporter.get("command"))).containsExactly("--path.rootfs=/host");

		List<String> allPorts = services.values().stream()
			.flatMap(service -> AwsMonitoringBundleConfigurationTest
				.<String>list(map(service).get("ports")).stream())
			.toList();
		assertThat(allPorts).containsExactlyInAnyOrder(
			"127.0.0.1:9090:9090", "127.0.0.1:3000:3000", "9100:9100");

		String composeText = Files.readString(COMPOSE_FILE);
		assertThat(composeText)
			.doesNotContain("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "privileged:",
				"0.0.0.0:9090", "0.0.0.0:3000");
	}

	@Test
	void monitoringBundlePreservesRawGrafanaPasswordBytesDuringComposeResolution()
		throws Exception {
		Path tempDirectory = createProtectedTempDirectory();
		try {
			Path runtimeEnv = writeProtectedFile(tempDirectory.resolve("monitoring.env"),
				"GRAFANA_ADMIN_PASSWORD=" + SYNTHETIC_PASSWORD + "\n");
			Path composeOutput = writeProtectedFile(tempDirectory.resolve("compose.json"), "");
			Path composeError = writeProtectedFile(tempDirectory.resolve("compose.stderr"), "");

			ProcessBuilder processBuilder = new ProcessBuilder(
				"docker", "compose", "-f", COMPOSE_FILE.toString(), "config", "--format", "json");
			Map<String, String> environment = processBuilder.environment();
			environment.put("PROMETHEUS_IMAGE", digestImage("prometheus", '3'));
			environment.put("GRAFANA_IMAGE", digestImage("grafana", '4'));
			environment.put("NODE_EXPORTER_IMAGE", digestImage("node-exporter", 'd'));
			environment.put("MONITORING_ENV_FILE", runtimeEnv.toAbsolutePath().toString());
			environment.put("TASK6_COLLISION", "ambient-value-must-not-replace-file-bytes");
			processBuilder.redirectOutput(composeOutput.toFile());
			processBuilder.redirectError(composeError.toFile());

			int exitCode = processBuilder.start().waitFor();
			assertThat(exitCode)
				.withFailMessage("Docker Compose did not resolve the monitoring bundle")
				.isZero();
			String composeErrorText = Files.readString(composeError);
			assertThat(List.of(
				SYNTHETIC_PASSWORD,
				CANONICAL_SYNTHETIC_PASSWORD,
				"ambient-value-must-not-replace-file-bytes",
				"double quoted",
				"single quoted",
				"literal suffix").stream().noneMatch(composeErrorText::contains))
				.withFailMessage("Docker Compose disclosed the synthetic Grafana password")
				.isTrue();

			JsonNode resolved = new ObjectMapper().readTree(composeOutput.toFile());
			String effectivePassword = resolved.path("services")
				.path("grafana")
				.path("environment")
				.path("GRAFANA_ADMIN_PASSWORD")
				.asText();
			assertThat(effectivePassword.equals(CANONICAL_SYNTHETIC_PASSWORD))
				.withFailMessage("Effective Grafana password did not preserve the runtime env bytes")
				.isTrue();
		}
		finally {
			deleteRecursively(tempDirectory);
		}
	}

	private void assertEc2Discovery(Map<String, Object> job, int port) {
		assertThat(list(job.get("ec2_sd_configs"))).containsExactly(Map.of(
			"region", "ap-northeast-2",
			"port", port));
	}

	private String digestImage(String name, char digestCharacter) {
		return "registry.example.invalid/airbob/" + name + "@sha256:"
			+ String.valueOf(digestCharacter).repeat(64);
	}

	private Path writeProtectedFile(Path path, String contents) throws IOException {
		Files.createFile(path,
			PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		return Files.writeString(path, contents, StandardCharsets.UTF_8,
			StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
	}

	private Path createProtectedTempDirectory() throws IOException {
		return Files.createTempDirectory("airbob-monitoring-env-",
			PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
	}

	private void deleteRecursively(Path directory) throws IOException {
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private Map<String, Object> keep(String sourceLabel, String regex) {
		return Map.of(
			"source_labels", List.of(sourceLabel),
			"regex", regex,
			"action", "keep");
	}

	private void assertService(Map<String, Object> service, String image, String memory) {
		assertThat(service)
			.containsEntry("image", image)
			.containsEntry("platform", "linux/amd64")
			.containsEntry("mem_limit", memory)
			.containsEntry("memswap_limit", memory)
			.containsEntry("restart", "unless-stopped")
			.doesNotContainKeys("build", "profiles", "privileged", "network_mode");
	}

	private Map<String, Object> datasource(Path path) throws IOException {
		Map<String, Object> configuration = yaml(path);
		assertThat(configuration).containsEntry("apiVersion", 1);
		return map(list(configuration.get("datasources")).getFirst());
	}

	private Map<String, Object> job(List<Map<String, Object>> jobs, String name) {
		return jobs.stream()
			.filter(candidate -> name.equals(candidate.get("job_name")))
			.findFirst()
			.orElseThrow();
	}

	private Map<String, Object> yaml(Path path) throws IOException {
		try (var reader = Files.newBufferedReader(path)) {
			return new Yaml().load(reader);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> List<T> list(Object value) {
		return value == null ? List.of() : (List<T>)value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>)value;
	}
}
