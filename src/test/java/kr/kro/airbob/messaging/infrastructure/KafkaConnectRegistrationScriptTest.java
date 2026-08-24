package kr.kro.airbob.messaging.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class KafkaConnectRegistrationScriptTest {

	private static final Path SCRIPT =
		Path.of("docker/debezium/register-connector.sh").toAbsolutePath();

	@TempDir
	Path tempDir;

	private Path connectorConfig;
	private Path fakeCurl;

	@BeforeEach
	void setUp() throws IOException {
		connectorConfig = tempDir.resolve("connector.json");
		Files.writeString(connectorConfig, "{\"name\":\"airbob-outbox-connector\"}\n");
		fakeCurl = executable("fake-curl", FAKE_CURL);
	}

	@Test
	@DisplayName("connector 등록 PUT과 상태 GET에 기본 연결 및 전송 제한 시간을 적용한다")
	void boundsRegistrationAndStatusRequestsByDefault() throws Exception {
		Execution execution = execute("running", Map.of());

		assertThat(execution.completed()).isTrue();
		assertThat(execution.exitCode()).withFailMessage(execution.output()).isZero();
		assertThat(Files.readAllLines(tempDir.resolve("curl-invocations.log")))
			.hasSize(2)
			.allSatisfy(invocation -> assertThat(invocation)
				.contains("--connect-timeout 5", "--max-time 10"));
	}

	@Test
	@DisplayName("connector 등록 PUT과 상태 GET의 연결 및 전송 제한 시간을 조정할 수 있다")
	void configuresRegistrationAndStatusRequestBudgets() throws Exception {
		Execution execution = execute("running", Map.of(
			"CONNECTOR_HTTP_CONNECT_TIMEOUT_SECONDS", "3",
			"CONNECTOR_HTTP_MAX_TIME_SECONDS", "7"
		));

		assertThat(execution.completed()).isTrue();
		assertThat(execution.exitCode()).withFailMessage(execution.output()).isZero();
		assertThat(Files.readAllLines(tempDir.resolve("curl-invocations.log")))
			.hasSize(2)
			.allSatisfy(invocation -> assertThat(invocation)
				.contains("--connect-timeout 3", "--max-time 7"));
	}

	@ParameterizedTest(name = "{0}={1} 거부")
	@MethodSource("invalidTimeouts")
	@DisplayName("HTTP 제한 시간은 요청 전에 양의 정수인지 검증한다")
	void rejectsInvalidTimeouts(String variable, String value) throws Exception {
		Execution execution = execute("running", Map.of(variable, value));

		assertThat(execution.completed()).isTrue();
		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.output()).contains(variable + " must be a positive integer");
		assertThat(tempDir.resolve("curl-invocations.log")).doesNotExist();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"hang-registration", "hang-status"})
	@DisplayName("등록이나 상태 요청이 응답하지 않아도 전송 제한 시간 안에 실패한다")
	void timesOutHungConnectRequests(String scenario) throws Exception {
		Execution execution = execute(scenario, Map.of(
			"CONNECTOR_HTTP_CONNECT_TIMEOUT_SECONDS", "1",
			"CONNECTOR_HTTP_MAX_TIME_SECONDS", "1"
		));

		assertThat(execution.completed()).isTrue();
		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.elapsed()).isLessThan(Duration.ofSeconds(4));
		assertThat(Files.readString(tempDir.resolve("curl-invocations.log")))
			.contains("--connect-timeout 1", "--max-time 1");
	}

	private static Stream<Arguments> invalidTimeouts() {
		return Stream.of(
			Arguments.of("CONNECTOR_HTTP_CONNECT_TIMEOUT_SECONDS", "0"),
			Arguments.of("CONNECTOR_HTTP_CONNECT_TIMEOUT_SECONDS", "1.5"),
			Arguments.of("CONNECTOR_HTTP_MAX_TIME_SECONDS", "-1"),
			Arguments.of("CONNECTOR_HTTP_MAX_TIME_SECONDS", "seconds")
		);
	}

	private Execution execute(String scenario, Map<String, String> environmentOverrides) throws Exception {
		Map<String, String> environment = new HashMap<>();
		environment.put("FAKE_STATE_DIR", tempDir.toString());
		environment.put("FAKE_SCENARIO", scenario);
		environment.put("CURL_BIN", fakeCurl.toString());
		environment.put("CONNECTOR_CONFIG_FILE", connectorConfig.toString());
		environment.put("CONNECT_URL", "http://connect:8083");
		environment.put("CONNECTOR_NAME", "airbob-outbox-connector");
		environment.put("CONNECTOR_REGISTRATION_ATTEMPTS", "1");
		environment.put("CONNECTOR_STATUS_ATTEMPTS", "1");
		environment.put("CONNECTOR_STATUS_DELAY_SECONDS", "0");
		environment.putAll(environmentOverrides);

		ProcessBuilder builder = new ProcessBuilder("/bin/sh", SCRIPT.toString())
			.redirectErrorStream(true);
		builder.environment().remove("CONNECTOR_HTTP_CONNECT_TIMEOUT_SECONDS");
		builder.environment().remove("CONNECTOR_HTTP_MAX_TIME_SECONDS");
		builder.environment().putAll(environment);
		long startedAtNanos = System.nanoTime();
		Process process = builder.start();
		boolean completed = process.waitFor(4, TimeUnit.SECONDS);
		if (!completed) {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			process.waitFor(1, TimeUnit.SECONDS);
		}
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new Execution(
			completed,
			completed ? process.exitValue() : -1,
			output,
			Duration.ofNanos(System.nanoTime() - startedAtNanos)
		);
	}

	private Path executable(String name, String contents) throws IOException {
		Path path = tempDir.resolve(name);
		Files.writeString(path, contents);
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
		return path;
	}

	private record Execution(boolean completed, int exitCode, String output, Duration elapsed) {
	}

	private static final String FAKE_CURL = """
		#!/bin/sh
		set -eu
		printf '%s\n' "$*" >> "$FAKE_STATE_DIR/curl-invocations.log"
		max_time=
		url=
		while [ "$#" -gt 0 ]; do
		  case "$1" in
		    --connect-timeout|--max-time)
		      [ "$1" != "--max-time" ] || max_time="$2"
		      shift 2
		      ;;
		    --request|--header|--data-binary) shift 2 ;;
		    --fail|--silent|--show-error) shift ;;
		    *) url="$1"; shift ;;
		  esac
		done

		hang() {
		  if [ -z "$max_time" ]; then
		    sleep 30
		  else
		    sleep "$max_time"
		  fi
		  exit 28
		}

		case "$FAKE_SCENARIO:$url" in
		  hang-registration:*/config) hang ;;
		  hang-status:*/status) hang ;;
		esac

		case "$url" in
		  */status)
		    printf '%s' '{"connector":{"state":"RUNNING"},"tasks":[{"id":0,"state":"RUNNING"}],"type":"source"}'
		    ;;
		esac
		""";
}
