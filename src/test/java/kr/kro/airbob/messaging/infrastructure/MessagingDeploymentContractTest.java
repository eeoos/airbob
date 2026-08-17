package kr.kro.airbob.messaging.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.payment.messaging.kafka.PaymentOperationExecutionListener;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.alert.infrastructure.kafka.OperatorAlertKafkaListener;
import kr.kro.airbob.search.messaging.event.AccommodationSearchRefreshRequestedV1;
import kr.kro.airbob.search.messaging.kafka.AccommodationSearchRefreshListener;

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
		assertThat(text(config, "predicates")).isEqualTo("IsOutboxTable");
		assertThat(text(config, "predicates.IsOutboxTable.type"))
			.isEqualTo("org.apache.kafka.connect.transforms.predicates.TopicNameMatches");
		assertThat(text(config, "predicates.IsOutboxTable.pattern"))
			.isEqualTo("airbob_outbox\\.airbobdb\\.outbox");
		assertThat(text(config, "transforms.outbox.predicate")).isEqualTo("IsOutboxTable");
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
	@DisplayName("outbox destination과 listener topic은 override 없이 같은 canonical 상수를 사용한다")
	void keepsOutboxDestinationsAndListenersOnProvisionedTopics() throws Exception {
		assertThat(PaymentOperationExecutionRequestedV1.DESCRIPTOR.destination())
			.isEqualTo(PaymentOperationExecutionRequestedV1.TOPIC);
		assertThat(AccommodationSearchRefreshRequestedV1.DESCRIPTOR.destination())
			.isEqualTo(AccommodationSearchRefreshRequestedV1.TOPIC);
		assertThat(OperatorAlertRequestedV1.DESCRIPTOR.destination())
			.isEqualTo(OperatorAlertRequestedV1.TOPIC);
		assertCanonicalListenerTopic(
			PaymentOperationExecutionListener.class,
			PaymentOperationExecutionRequestedV1.TOPIC);
		assertCanonicalListenerTopic(
			AccommodationSearchRefreshListener.class,
			AccommodationSearchRefreshRequestedV1.TOPIC);
		assertCanonicalListenerTopic(
			OperatorAlertKafkaListener.class,
			OperatorAlertRequestedV1.TOPIC);

		List.of(
			PaymentOperationExecutionRequestedV1.TOPIC,
			AccommodationSearchRefreshRequestedV1.TOPIC,
			OperatorAlertRequestedV1.TOPIC
		).forEach(topic -> assertThat(BUSINESS_TOPICS)
			.contains(topic, topic + ".RETRY", topic + ".DLT"));
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
	@DisplayName("애플리케이션은 하나의 bootstrap 주소와 canonical String payload serializer를 사용한다")
	void normalizesKafkaClientProperties() throws IOException {
		String application = read("src/main/resources/application.yaml");
		assertThat(application)
			.contains("value-serializer: org.apache.kafka.common.serialization.StringSerializer")
			.doesNotContain("value-serializer: org.springframework.kafka.support.serializer.JsonSerializer");

		for (String profile : List.of("dev", "aws", "oci")) {
			String profileConfig = read("src/main/resources/application-" + profile + ".yaml");
			assertThat(profileConfig)
				.contains("  kafka:\n    bootstrap-servers:")
				.doesNotContain("group-id: payment-service-group")
				.doesNotContain("consumer:\n      bootstrap-servers:")
				.doesNotContain("producer:\n      bootstrap-servers:");
		}
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

	private void assertCanonicalListenerTopic(Class<?> listenerType, String expectedTopic)
		throws NoSuchMethodException {
		KafkaListener listener = listenerType
			.getMethod("handle", String.class, Acknowledgment.class)
			.getAnnotation(KafkaListener.class);
		assertThat(listener).isNotNull();
		assertThat(listener.topics()).containsExactly(expectedTopic);
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
