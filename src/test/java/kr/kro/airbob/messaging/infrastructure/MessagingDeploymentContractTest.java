package kr.kro.airbob.messaging.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class MessagingDeploymentContractTest {

	private static final Path CONNECTOR_CONFIG = Path.of("debezium-config/outbox-connector.json");
	private static final List<String> BUSINESS_TOPICS = List.of(
		"PAYMENT_OPERATION.events",
		"PAYMENT_OPERATION.events.RETRY",
		"PAYMENT_OPERATION.events.DLT",
		"ACCOMMODATION_INDEX.events",
		"ACCOMMODATION_INDEX.events.RETRY",
		"ACCOMMODATION_INDEX.events.DLT",
		"OPERATOR_ALERT.events",
		"OPERATOR_ALERT.events.RETRY",
		"OPERATOR_ALERT.events.DLT"
	);

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("하나의 connector 설정이 canonical outbox 컬럼을 Kafka record 계약으로 변환한다")
	void routesCanonicalOutboxColumns() throws IOException {
		JsonNode config = objectMapper.readTree(CONNECTOR_CONFIG.toFile());

		assertThat(text(config, "database.user")).isEqualTo("${env:DEBEZIUM_DATABASE_USER}");
		assertThat(text(config, "database.password")).isEqualTo("${env:DEBEZIUM_DATABASE_PASSWORD}");
		assertThat(text(config, "schema.history.internal.kafka.bootstrap.servers"))
			.isEqualTo("${env:DEBEZIUM_KAFKA_BOOTSTRAP_SERVERS}");
		assertThat(text(config, "heartbeat.interval.ms")).isEqualTo("10000");
		assertThat(text(config, "key.converter"))
			.isEqualTo("org.apache.kafka.connect.storage.StringConverter");
		assertThat(text(config, "value.converter"))
			.isEqualTo("org.apache.kafka.connect.storage.StringConverter");
		assertThat(text(config, "header.converter"))
			.isEqualTo("org.apache.kafka.connect.storage.StringConverter");
		assertThat(text(config, "transforms.outbox.route.by.field")).isEqualTo("destination");
		assertThat(text(config, "transforms.outbox.route.topic.replacement")).isEqualTo("${routedByValue}");
		assertThat(text(config, "transforms.outbox.table.op.invalid.behavior")).isEqualTo("fatal");
		assertThat(text(config, "transforms.outbox.table.field.event.id")).isEqualTo("event_id");
		assertThat(text(config, "transforms.outbox.table.field.event.key")).isEqualTo("partition_key");
		assertThat(text(config, "transforms.outbox.table.field.event.payload")).isEqualTo("payload");
		assertThat(text(config, "transforms.outbox.table.field.event.timestamp")).isEqualTo("occurred_at");
		assertThat(text(config, "transforms.outbox.table.fields.additional.placement"))
			.isEqualTo("event_type:header:eventType,event_version:header:eventVersion,"
				+ "aggregate_type:header:aggregateType,aggregate_id:header:aggregateId");
	}

	@Test
	@DisplayName("connector 설정은 drift 가능한 local과 OCI 복사본 없이 하나만 존재한다")
	void keepsSingleConnectorConfig() throws IOException {
		try (var configs = Files.walk(Path.of("debezium-config"))) {
			assertThat(configs.filter(path -> path.toString().endsWith(".json")))
				.containsExactly(CONNECTOR_CONFIG);
		}

		assertThat(Path.of("docker/debezium/connector-local.json")).doesNotExist();
		assertThat(Path.of("docker/debezium/connector-oci.json")).doesNotExist();
		assertThat(Path.of("debezium-config/register-mysql-connector.json")).doesNotExist();
	}

	@Test
	@DisplayName("Kafka Connect worker는 env ConfigProvider와 raw String converter를 사용한다")
	void configuresConnectWorkerContract() throws IOException {
		String worker = read("docker/debezium/connect-distributed.properties");

		assertThat(worker)
			.contains("config.providers=env")
			.contains("config.providers.env.class=org.apache.kafka.common.config.provider.EnvVarConfigProvider")
			.contains("config.providers.env.param.allowlist.pattern=^(DEBEZIUM_DATABASE_USER|"
				+ "DEBEZIUM_DATABASE_PASSWORD|DEBEZIUM_KAFKA_BOOTSTRAP_SERVERS)$")
			.contains("bootstrap.servers=${env:DEBEZIUM_KAFKA_BOOTSTRAP_SERVERS}")
			.contains("key.converter=org.apache.kafka.connect.storage.StringConverter")
			.contains("value.converter=org.apache.kafka.connect.storage.StringConverter")
			.contains("topic.creation.enable=false");
	}

	@Test
	@DisplayName("topic bootstrap은 세 스트림의 primary retry DLT를 같은 partition 수로 검증한다")
	void provisionsBusinessTopicsExplicitly() throws IOException {
		String script = read("docker/kafka/init-topics.sh");

		assertThat(script)
			.contains("readonly BUSINESS_TOPIC_PARTITIONS=3")
			.contains("--if-not-exists")
			.contains("\"__debezium-heartbeat.airbob_outbox\" 1")
			.contains("assert_partition_count \"$topic\" \"$BUSINESS_TOPIC_PARTITIONS\"");
		BUSINESS_TOPICS.forEach(topic -> assertThat(script).contains("\"" + topic + "\""));
	}

	@Test
	@DisplayName("compose는 자동 topic 생성을 끄고 topic과 connector init 성공 후 서비스한다")
	void gatesServicesOnMessagingBootstrap() throws IOException {
		String local = read("docker-compose.yml");
		String oci = read("docker-compose.oci.yml");

		assertThat(local).contains("KAFKA_AUTO_CREATE_TOPICS_ENABLE: \"false\"");
		assertThat(oci).contains("KAFKA_AUTO_CREATE_TOPICS_ENABLE: \"false\"");
		assertThat(serviceBlock(local, "debezium"))
			.contains("kafka-topic-init:", "condition: service_completed_successfully");
		assertThat(serviceBlock(oci, "debezium"))
			.contains("kafka-topic-init:", "condition: service_completed_successfully");
		assertThat(serviceBlock(oci, "app"))
			.contains("kafka-topic-init:", "debezium-connector-init:",
				"condition: service_completed_successfully",
				"SPRING_KAFKA_ADMIN_AUTO_CREATE=false",
				"SPRING_KAFKA_LISTENER_MISSING_TOPICS_FATAL=true");
	}

	@Test
	@DisplayName("connector init은 PUT 후 connector와 task의 RUNNING 상태를 모두 확인한다")
	void registersConnectorIdempotently() throws IOException {
		String script = read("docker/debezium/register-connector.sh");

		assertThat(script)
			.contains("--request PUT")
			.contains("/connectors/$CONNECTOR_NAME/config")
			.contains("/connectors/$CONNECTOR_NAME/status")
			.contains("Connector and all connector tasks are RUNNING");
	}

	@Test
	@DisplayName("CDC 계정은 전체 권한 없이 snapshot 조회와 binlog replication 권한만 가진다")
	void grantsLeastPrivilegeToCdcUser() throws IOException {
		String init = read("docker/mysql/init/01-create-infrastructure-users.sh");

		assertThat(init)
			.contains("GRANT SELECT ON")
			.contains("REPLICATION SLAVE", "REPLICATION CLIENT")
			.doesNotContain("GRANT ALL", "ALL PRIVILEGES");
	}

	private String text(JsonNode config, String property) {
		return config.path(property).asText();
	}

	private String read(String path) throws IOException {
		return Files.readString(Path.of(path));
	}

	private String serviceBlock(String compose, String serviceName) {
		String marker = "  " + serviceName + ":";
		int markerStart = compose.indexOf("\n" + marker + "\n");
		int start = compose.startsWith(marker + "\n")
			? 0
			: markerStart >= 0 ? markerStart + 1 : -1;
		assertThat(start).as("service %s exists", serviceName).isGreaterThanOrEqualTo(0);

		int end = compose.length();
		for (int cursor = compose.indexOf('\n', start) + 1; cursor > 0 && cursor < compose.length();) {
			int nextLine = compose.indexOf('\n', cursor);
			if (nextLine < 0) {
				nextLine = compose.length();
			}
			String line = compose.substring(cursor, nextLine);
			if (line.matches("  [a-zA-Z0-9_-]+:")) {
				end = cursor;
				break;
			}
			cursor = nextLine + 1;
		}
		return compose.substring(start, end);
	}
}
