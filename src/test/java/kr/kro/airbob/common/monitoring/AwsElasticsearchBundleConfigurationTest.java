package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AwsElasticsearchBundleConfigurationTest {

	private static final Path COMPOSE_FILE = Path.of(
		"infra", "aws", "bundles", "elasticsearch", "compose.yml");

	@Test
	void awsElasticsearchBundleProvidesOnePersistentNodeAndHostMetrics() throws IOException {
		assertThat(COMPOSE_FILE).exists();

		Map<String, Object> compose = yaml(COMPOSE_FILE);
		assertThat(compose.keySet()).containsExactlyInAnyOrder(
			"services", "volumes", "x-airbob-host-contracts");

		Map<String, Object> services = map(compose.get("services"));
		assertThat(services.keySet()).containsExactlyInAnyOrder(
			"elasticsearch", "elasticsearch-exporter", "node-exporter");
		assertThat(map(compose.get("volumes")).keySet())
			.containsExactly("elasticsearch-data");

		Map<String, Object> elasticsearch = map(services.get("elasticsearch"));
		assertThat(elasticsearch)
			.containsEntry("image", "${ELASTICSEARCH_IMAGE:?ELASTICSEARCH_IMAGE is required}")
			.containsEntry("platform", "linux/amd64")
			.containsEntry("mem_limit", "2G")
			.containsEntry("memswap_limit", "2G")
			.containsEntry("restart", "unless-stopped")
			.doesNotContainKeys("build", "command", "entrypoint", "profiles");
		assertThat(map(elasticsearch.get("environment"))).containsExactlyInAnyOrderEntriesOf(Map.of(
			"discovery.type", "single-node",
			"xpack.security.enabled", "false",
			"ES_JAVA_OPTS", "-Xms1g -Xmx1g"));
		assertThat(list(elasticsearch.get("ports"))).containsExactly("9200:9200");
		assertThat(list(elasticsearch.get("volumes")))
			.containsExactly("elasticsearch-data:/usr/share/elasticsearch/data");
		assertThat(map(map(elasticsearch.get("ulimits")).get("nofile")))
			.containsExactlyInAnyOrderEntriesOf(Map.of("soft", 65535, "hard", 65535));

		Map<String, Object> exporter = map(services.get("elasticsearch-exporter"));
		assertThat(exporter)
			.containsEntry("image",
				"${ELASTICSEARCH_EXPORTER_IMAGE:?ELASTICSEARCH_EXPORTER_IMAGE is required}")
			.containsEntry("platform", "linux/amd64")
			.containsEntry("mem_limit", "128M")
			.containsEntry("memswap_limit", "128M")
			.containsEntry("restart", "unless-stopped");
		assertThat(list(exporter.get("command")))
			.containsExactly("--es.uri=http://elasticsearch:9200");
		assertThat(list(exporter.get("ports"))).containsExactly("9114:9114");

		assertNodeExporter(map(services.get("node-exporter")));
		assertThat(services.values())
			.extracting(service -> map(service).get("image"))
			.containsExactlyInAnyOrder(
				"${ELASTICSEARCH_IMAGE:?ELASTICSEARCH_IMAGE is required}",
				"${ELASTICSEARCH_EXPORTER_IMAGE:?ELASTICSEARCH_EXPORTER_IMAGE is required}",
				"${NODE_EXPORTER_IMAGE:?NODE_EXPORTER_IMAGE is required}");
	}

	@Test
	void awsElasticsearchBundleCarriesTheRequiredHostVirtualMemoryMapLimit() throws IOException {
		Map<String, Object> compose = yaml(COMPOSE_FILE);
		assertThat(compose).containsKey("x-airbob-host-contracts");
		Map<String, Object> hostContracts = map(compose.get("x-airbob-host-contracts"));

		assertThat(hostContracts.keySet()).containsExactly("elasticsearch");
		assertThat(map(hostContracts.get("elasticsearch")))
			.containsExactly(Map.entry("vm.max_map_count", 1048576));
	}

	private void assertNodeExporter(Map<String, Object> nodeExporter) {
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
