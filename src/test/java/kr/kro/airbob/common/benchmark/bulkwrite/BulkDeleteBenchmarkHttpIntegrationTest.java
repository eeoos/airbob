package kr.kro.airbob.common.benchmark.bulkwrite;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.awspring.cloud.s3.S3Template;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.aws.s3.enabled=false", "benchmark.bulk-write.enabled=true",
	"benchmark.bulk-write.token=bulk-delete-http-test-token-1234567890",
	"benchmark.bulk-write.allowed-schema=airbob_bulk_write_benchmark",
	"benchmark.bulk-delete.app-commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
})
@ActiveProfiles({"test", "bulk-delete-benchmark"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BulkDeleteBenchmarkHttpIntegrationTest {

	private static final String PREFIX = "/api/v2/admin/benchmarks/bulk-write/";
	private static final String TOKEN = "bulk-delete-http-test-token-1234567890";
	private static final String EMAIL = "bulk-delete-http@example.test";
	private static final String PASSWORD = "bulk-delete-http-password";
	@Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_bulk_write_benchmark");
	@Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
		.withExposedPorts(6379);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration").load().migrate();
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.datasource.hikari.data-source-properties.rewriteBatchedStatements", () -> "true");
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
	}

	@Autowired TestRestTemplate http;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper mapper;
	@Autowired KafkaListenerEndpointRegistry listeners;
	@LocalServerPort int port;
	@MockitoBean ElasticsearchClient elasticsearchClient;
	@MockitoBean ElasticsearchOperations elasticsearchOperations;
	@MockitoBean AccommodationSearchRepository accommodationSearchRepository;
	@MockitoBean S3Template s3Template;
	private HttpHeaders headers;

	@BeforeEach
	void loginAdmin() {
		jdbc.update("""
			INSERT INTO member (email, password, nickname, role, status, created_at, updated_at)
			VALUES (?, ?, 'bulk-delete-http', 'ADMIN', 'ACTIVE', NOW(6), NOW(6))
			""", EMAIL, BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4)));
		var login = http.postForEntity("/api/v1/auth/login", Map.of("email", EMAIL, "password", PASSWORD), JsonNode.class);
		assertThat(login.getStatusCode().is2xxSuccessful()).isTrue();
		headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0]);
		headers.set(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN);
		var runtime = http.exchange(PREFIX + "runtime", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
		assertThat(runtime.getStatusCode().is2xxSuccessful()).isTrue();
		headers.set(BulkDeleteBenchmarkRuntimeIdentity.HEADER_NAME,
			runtime.getBody().at("/data/runtime_id").asText());
	}

	@AfterEach
	void cleanup() {
		http.postForEntity("/api/v1/auth/logout", new HttpEntity<>(null, headers), JsonNode.class);
		jdbc.update("DELETE FROM member");
	}

	@Test
	void reportsActualRuntimeAndProtectsTheExperimentRoutes() {
		var first = http.exchange(PREFIX + "runtime", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
		assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
		JsonNode runtime = first.getBody().get("data");
		assertThat(runtime.get("environment").asText()).isEqualTo("local");
		assertThat(runtime.get("schema_label").asText()).isEqualTo("airbob_bulk_write_benchmark");
		assertThat(runtime.get("flyway_version").asText()).isEqualTo("28");
		assertThat(runtime.get("database_id").asText()).isEqualTo(jdbc.queryForObject("SELECT @@server_uuid", String.class));
		assertThat(runtime.get("rewrite_batched_statements").asBoolean()).isTrue();
		var second = http.exchange(PREFIX + "runtime", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
		assertThat(second.getBody().get("data")).isEqualTo(runtime);
		assertThat(http.getForEntity(PREFIX + "runtime", String.class).getStatusCode().is4xxClientError()).isTrue();
		var badToken = new HttpHeaders(headers);
		badToken.set(BulkWriteBenchmarkAccessGuard.HEADER_NAME, "wrong");
		assertThat(http.exchange(PREFIX + "runtime", HttpMethod.GET, new HttpEntity<>(badToken), String.class)
			.getStatusCode().is4xxClientError()).isTrue();
		var wrongRuntime = new HttpHeaders(headers);
		wrongRuntime.set(BulkDeleteBenchmarkRuntimeIdentity.HEADER_NAME, "another-instance");
		assertThat(http.postForEntity(PREFIX + "wishlist-delete",
			new HttpEntity<>(Map.of("variant", "AFTER", "dataset_size", 3), wrongRuntime), String.class)
			.getStatusCode().value()).isEqualTo(409);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wishlist", Long.class)).isZero();
		assertThat(http.postForEntity(PREFIX + "reservation-history-insert",
			new HttpEntity<>(Map.of("variant", "AFTER", "dataset_size", 1), headers), String.class)
			.getStatusCode().value()).isEqualTo(404);
		assertThat(listeners.getAllListenerContainers()).isNotEmpty()
			.allSatisfy(container -> assertThat(container.isRunning()).isFalse());
	}

	@Test
	void everyDeleteVariantWorksThroughSessionAndTokenChecksAndCleansItsFixtures() {
		for (String variant : new String[] {"BEFORE", "AFTER"}) {
			assertSuccessfulDelete("wishlist-delete", Map.of("variant", variant, "dataset_size", 3));
			for (String mode : new String[] {"DELETE_ONLY", "FULL_REPLACEMENT"}) {
				assertSuccessfulDelete("accommodation-amenity-delete",
					Map.of("variant", variant, "measurement", mode, "dataset_size", 3));
			}
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "BULK_DELETE_VERIFY_RUNNER", matches = "true")
	void runsTheRealPythonK6SuiteAgainstMySqlAndRedis() throws Exception {
		Path privateFiles = Files.createTempDirectory("bulk-delete-e2e-");
		try {
			var config = (ObjectNode)mapper.readTree(
				Path.of("load-test/k6/bulk-write/local-experiment.example.json").toFile());
			String runId = "bulk-delete-smoke-" + System.nanoTime();
			config.put("example", false).put("baseUrl", "http://127.0.0.1:" + port)
				.put("runId", runId).put("rounds", 2).put("samples", 1).put("warmupSamples", 1);
			config.putArray("wishlistSizes").add(3);
			config.putArray("amenitySizes").add(3);
			ObjectNode credentials = (ObjectNode)config.get("credentials");
			for (var entry : Map.of("emailFile", EMAIL, "passwordFile", PASSWORD, "tokenFile", TOKEN).entrySet()) {
				Path file = privateFiles.resolve(entry.getKey());
				Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
				Files.writeString(file, entry.getValue());
				credentials.put(entry.getKey(), file.toString());
			}
			Path configPath = privateFiles.resolve("config.json");
			mapper.writeValue(configPath.toFile(), config);
			Path log = Path.of("build/bulk-delete-e2e.log");
			var process = new ProcessBuilder("python3", "load-test/k6/bulk-write/run-experiments.py", "run",
				"--config", configPath.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
			if (!process.waitFor(180, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				fail("Python/k6 suite timed out; see " + log);
			}
			assertThat(process.exitValue()).as(Files.readString(log)).isZero();
			JsonNode report = mapper.readTree(Path.of("build/k6/bulk-delete", runId, "comparison.json").toFile());
			assertThat(report.get("state").asText()).isEqualTo("measured");
			assertThat(report.get("comparisons")).hasSize(3);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isZero();
		} finally {
			try (var files = Files.list(privateFiles)) {
				for (Path file : files.toList()) Files.delete(file);
			}
			Files.delete(privateFiles);
		}
	}

	private void assertSuccessfulDelete(String endpoint, Map<String, Object> request) {
		var result = http.postForEntity(PREFIX + endpoint, new HttpEntity<>(request, headers), JsonNode.class);
		assertThat(result.getStatusCode().is2xxSuccessful()).as(result.toString()).isTrue();
		assertThat(result.getBody().at("/data/verification_succeeded").asBoolean()).isTrue();
		for (String table : new String[] {"outbox", "wishlist", "accommodation", "accommodation_amenity"}) {
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).as(table).isZero();
		}
	}
}
