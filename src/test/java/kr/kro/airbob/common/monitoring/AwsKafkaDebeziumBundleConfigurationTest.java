package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class AwsKafkaDebeziumBundleConfigurationTest {

	private static final Path KAFKA_DIRECTORY = Path.of(
		"infra", "aws", "bundles", "kafka");
	private static final Path DEBEZIUM_DIRECTORY = Path.of(
		"infra", "aws", "bundles", "debezium");
	private static final Path KAFKA_COMPOSE = KAFKA_DIRECTORY.resolve("compose.yml");
	private static final Path KAFKA_JMX = KAFKA_DIRECTORY.resolve("jmx-exporter.yml");
	private static final Path DEBEZIUM_COMPOSE = DEBEZIUM_DIRECTORY.resolve("compose.yml");
	private static final Path DEBEZIUM_WORKER = DEBEZIUM_DIRECTORY.resolve(
		"connect-distributed.aws.properties");
	private static final Path DEBEZIUM_CONNECTOR = DEBEZIUM_DIRECTORY.resolve(
		"connector.aws.json.tmpl");
	private static final Path DEBEZIUM_JMX = DEBEZIUM_DIRECTORY.resolve("jmx-exporter.yml");
	private static final String KAFKA_BOOTSTRAP = "kafka.lab.airbob.internal:9092";
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void kafkaBundleExposesSeparateInternalAndVpcListenersWithJmxMonitoring() throws IOException {
		assertThat(KAFKA_COMPOSE).exists();
		assertThat(KAFKA_JMX).exists();

		Map<String, Object> compose = yaml(KAFKA_COMPOSE);
		assertJmxImageContract(compose);
		Map<String, Object> services = map(compose.get("services"));
		assertThat(services.keySet()).containsExactlyInAnyOrder("kafka", "node-exporter");

		Map<String, Object> kafka = map(services.get("kafka"));
		assertThat(kafka)
			.containsEntry("image", "${KAFKA_IMAGE:?KAFKA_IMAGE is required}")
			.containsEntry("platform", "linux/amd64")
			.containsEntry("mem_limit", "1536M")
			.containsEntry("memswap_limit", "1536M")
			.containsEntry("restart", "unless-stopped");
		Map<String, Object> environment = map(kafka.get("environment"));
		assertThat(environment)
			.containsEntry("KAFKA_NODE_ID", 1)
			.containsEntry("KAFKA_PROCESS_ROLES", "broker,controller")
			.containsEntry("KAFKA_LISTENERS",
				"INTERNAL://:19092,VPC://:9092,CONTROLLER://:9093")
			.containsEntry("KAFKA_ADVERTISED_LISTENERS",
				"INTERNAL://kafka:19092,VPC://kafka.lab.airbob.internal:9092")
			.containsEntry("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL")
			.containsEntry("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
			.containsEntry("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
				"INTERNAL:PLAINTEXT,VPC:PLAINTEXT,CONTROLLER:PLAINTEXT")
			.containsEntry("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@kafka:9093")
			.containsEntry("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", 1)
			.containsEntry("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", 1)
			.containsEntry("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", 1)
			.containsEntry("KAFKA_LOG_DIRS", "/var/lib/kafka/data")
			.containsEntry("CLUSTER_ID", "airbob-performance-lab")
			.containsEntry("KAFKA_HEAP_OPTS", "-Xms1g -Xmx1g")
			.containsEntry("KAFKA_OPTS",
				"-javaagent:/opt/jmx/jmx_prometheus_javaagent.jar=7071:/opt/jmx/kafka.yml");
		assertThat(list(kafka.get("ports"))).containsExactly("9092:9092", "7071:7071");
		assertThat(list(kafka.get("volumes"))).containsExactlyInAnyOrder(
			"kafka-data:/var/lib/kafka/data",
			"./jmx-exporter.yml:/opt/jmx/kafka.yml:ro");
		assertHealthCheck(kafka, 30);
		assertThat(map(compose.get("volumes")).keySet()).containsExactly("kafka-data");
		assertNodeExporter(map(services.get("node-exporter")));
		assertJmxConfiguration(KAFKA_JMX);
	}

	@Test
	void debeziumBundleUsesTheVpcKafkaEndpointAndKeepsRestOnLoopback() throws IOException {
		assertThat(DEBEZIUM_COMPOSE).exists();
		assertThat(DEBEZIUM_WORKER).exists();
		assertThat(DEBEZIUM_JMX).exists();

		Map<String, Object> compose = yaml(DEBEZIUM_COMPOSE);
		assertJmxImageContract(compose);
		Map<String, Object> services = map(compose.get("services"));
		assertThat(services.keySet()).containsExactlyInAnyOrder("debezium", "node-exporter");

		Map<String, Object> debezium = map(services.get("debezium"));
		assertThat(debezium)
			.containsEntry("image", "${DEBEZIUM_IMAGE:?DEBEZIUM_IMAGE is required}")
			.containsEntry("platform", "linux/amd64")
			.containsEntry("mem_limit", "768M")
			.containsEntry("memswap_limit", "768M")
			.containsEntry("restart", "unless-stopped");
		assertThat(list(debezium.get("command"))).containsExactly(
			"/opt/kafka/bin/connect-distributed.sh",
			"/opt/kafka/config/connect-distributed.aws.properties");
		assertThat(map(debezium.get("environment")))
			.containsEntry("KAFKA_HEAP_OPTS", "-Xms512m -Xmx512m")
			.containsEntry("KAFKA_OPTS",
				"-javaagent:/opt/jmx/jmx_prometheus_javaagent.jar=9404:/opt/jmx/debezium.yml");
		assertThat(list(debezium.get("ports")))
			.containsExactly("127.0.0.1:8083:8083", "9404:9404");
		assertThat(list(debezium.get("volumes"))).containsExactlyInAnyOrder(
			"./connect-distributed.aws.properties:/opt/kafka/config/connect-distributed.aws.properties:ro",
			"./jmx-exporter.yml:/opt/jmx/debezium.yml:ro");
		assertHealthCheck(debezium, 60);
		assertNodeExporter(map(services.get("node-exporter")));
		assertJmxConfiguration(DEBEZIUM_JMX);

		Properties worker = properties(DEBEZIUM_WORKER);
		assertThat(worker)
			.containsEntry("bootstrap.servers", KAFKA_BOOTSTRAP)
			.containsEntry("producer.bootstrap.servers", KAFKA_BOOTSTRAP)
			.containsEntry("consumer.bootstrap.servers", KAFKA_BOOTSTRAP)
			.containsEntry("group.id", "airbob-debezium-connect")
			.containsEntry("config.storage.topic", "airbob_debezium_configs")
			.containsEntry("offset.storage.topic", "airbob_debezium_offsets")
			.containsEntry("status.storage.topic", "airbob_debezium_statuses")
			.containsEntry("config.storage.replication.factor", "1")
			.containsEntry("offset.storage.replication.factor", "1")
			.containsEntry("status.storage.replication.factor", "1")
			.containsEntry("rest.advertised.host.name", "connect.lab.airbob.internal")
			.containsEntry("plugin.path", "/opt/kafka/connect-plugins")
			.containsEntry("key.converter", "org.apache.kafka.connect.json.JsonConverter")
			.containsEntry("key.converter.schemas.enable", "false")
			.containsEntry("value.converter", "org.apache.kafka.connect.storage.StringConverter")
			.containsEntry("offset.flush.interval.ms", "10000");
	}

	@Test
	void kafkaHealthProbeDoesNotReuseTheBrokerJmxAgentOrHeap() throws IOException {
		Map<String, Object> kafka = map(map(yaml(KAFKA_COMPOSE).get("services")).get("kafka"));
		Map<String, Object> environment = map(kafka.get("environment"));
		assertThat(environment)
			.containsEntry("KAFKA_HEAP_OPTS", "-Xms1g -Xmx1g")
			.containsEntry("KAFKA_OPTS",
				"-javaagent:/opt/jmx/jmx_prometheus_javaagent.jar=7071:/opt/jmx/kafka.yml");

		List<String> healthCommand = list(map(kafka.get("healthcheck")).get("test"));
		assertThat(healthCommand).hasSize(2).first().isEqualTo("CMD-SHELL");
		assertThat(healthCommand.get(1))
			.contains("KAFKA_OPTS=''")
			.contains("KAFKA_HEAP_OPTS='-Xms64m -Xmx64m'")
			.contains("/opt/kafka/bin/kafka-broker-api-versions.sh")
			.contains("--bootstrap-server localhost:19092");
	}

	@Test
	void debeziumHealthProbeRequiresConnectorsHttpStatus200WithinTheReadTimeout()
		throws IOException {
		Map<String, Object> debezium = map(
			map(yaml(DEBEZIUM_COMPOSE).get("services")).get("debezium"));
		List<String> healthCommand = list(map(debezium.get("healthcheck")).get("test"));
		assertThat(healthCommand).hasSize(4);
		assertThat(healthCommand.subList(0, 3)).containsExactly("CMD", "/bin/bash", "-ec");
		assertThat(healthCommand.get(3))
			.contains("GET /connectors HTTP/1.1")
			.contains("read -r -t 5 protocol status_code")
			.contains("[[ \"$$status_code\" == 200 ]]")
			.doesNotContain("curl")
			.doesNotContain("wget");
		assertThat(map(debezium.get("healthcheck"))).containsEntry("timeout", "10s");
	}

	@Test
	void connectorTemplateIsSecretFreeAndPreservesOutboxTopicRouting() throws IOException {
		assertThat(DEBEZIUM_CONNECTOR).exists();

		String template = Files.readString(DEBEZIUM_CONNECTOR);
		Map<String, Object> connector = objectMapper.readValue(
			template, new TypeReference<>() {});
		Map<String, Object> config = map(connector.get("config"));

		assertThat(connector.get("name")).isEqualTo("airbob-outbox-connector");
		assertThat(config)
			.containsEntry("database.hostname", "${RDS_ENDPOINT}")
			.containsEntry("database.user", "${DEBEZIUM_USERNAME}")
			.containsEntry("database.password", "${DEBEZIUM_PASSWORD}")
			.containsEntry("database.include.list", "airbobdb")
			.containsEntry("table.include.list", "airbobdb.outbox")
			.containsEntry("snapshot.mode", "no_data")
			.containsEntry("schema.history.internal.kafka.bootstrap.servers", KAFKA_BOOTSTRAP)
			.containsEntry("transforms.outbox.type", "io.debezium.transforms.outbox.EventRouter")
			.containsEntry("transforms.outbox.route.by.field", "aggregate_type")
			.containsEntry("transforms.outbox.route.topic.replacement", "${routedByValue}.events")
			.containsEntry("transforms.outbox.table.field.event.key", "aggregate_id")
			.containsEntry("transforms.outbox.table.field.event.payload", "payload")
			.containsEntry("transforms.outbox.table.fields.additional.placement",
				"event_type:header:eventType");

		List<String> placeholders = new ArrayList<>();
		Matcher matcher = PLACEHOLDER.matcher(template);
		while (matcher.find()) {
			placeholders.add(matcher.group(1));
		}
		assertThat(placeholders).containsExactlyInAnyOrder(
			"RDS_ENDPOINT", "DEBEZIUM_USERNAME", "DEBEZIUM_PASSWORD", "routedByValue");
	}

	@Test
	void awsKafkaAndDebeziumFilesDoNotContainCrossHostOrCredentialFallbacks() throws IOException {
		List<Path> files = List.of(
			KAFKA_COMPOSE, KAFKA_JMX,
			DEBEZIUM_COMPOSE, DEBEZIUM_WORKER, DEBEZIUM_CONNECTOR, DEBEZIUM_JMX);
		assertThat(files).allSatisfy(path -> assertThat(path).exists());

		String allFiles = files.stream()
			.map(this::readUnchecked)
			.reduce("", (left, right) -> left + "\n" + right);
		assertThat(allFiles)
			.doesNotContain("kafka:9092")
			.doesNotContain("database.hostname=mysql")
			.doesNotContain("\"database.hostname\": \"mysql\"")
			.doesNotContain("\"database.user\": \"debezium\"")
			.doesNotContain("\"database.password\": \"dbz\"")
			.doesNotContain("snapshot.mode=initial")
			.doesNotContain("\"snapshot.mode\": \"initial\"");
	}

	private void assertJmxImageContract(Map<String, Object> compose) {
		Map<String, Object> contract = map(map(compose.get("x-airbob-image-contracts"))
			.get("prometheus-jmx-exporter"));
		assertThat(contract)
			.containsEntry("version", "1.6.0")
			.containsEntry("agent-path", "/opt/jmx/jmx_prometheus_javaagent.jar");
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

	private void assertHealthCheck(Map<String, Object> service, int startPeriodSeconds) {
		Map<String, Object> healthcheck = map(service.get("healthcheck"));
		assertThat(list(healthcheck.get("test"))).isNotEmpty();
		assertThat(healthcheck)
			.containsEntry("interval", "30s")
			.containsEntry("timeout", "10s")
			.containsEntry("retries", 5)
			.containsEntry("start_period", startPeriodSeconds + "s");
	}

	private void assertJmxConfiguration(Path path) throws IOException {
		Map<String, Object> configuration = yaml(path);
		assertThat(configuration)
			.containsEntry("lowercaseOutputName", true)
			.containsEntry("lowercaseOutputLabelNames", true);
		assertThat(list(configuration.get("rules"))).isNotEmpty();
	}

	private Properties properties(Path path) throws IOException {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(path)) {
			properties.load(reader);
		}
		return properties;
	}

	private Map<String, Object> yaml(Path path) throws IOException {
		try (Reader reader = Files.newBufferedReader(path)) {
			return new Yaml().load(reader);
		}
	}

	private String readUnchecked(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException exception) {
			throw new IllegalStateException(exception);
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
