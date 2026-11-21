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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KafkaConnectMonitorScriptTest {

	private static final Path SCRIPT =
		Path.of("docker/debezium/monitor-connector.sh").toAbsolutePath();

	@TempDir
	Path tempDir;

	private Path fakeCurl;

	@BeforeEach
	void setUp() throws IOException {
		fakeCurl = executable("fake-curl", FAKE_CURL);
	}

	@Test
	@DisplayName("connector와 모든 task가 RUNNING일 때만 health state를 RUNNING으로 기록한다")
	void recordsHealthyOnlyWhenConnectorAndTasksRun() throws Exception {
		Execution execution = execute("running", Map.of("CONNECTOR_MONITOR_MAX_CHECKS", "1"));

		assertThat(execution.completed()).isTrue();
		assertThat(execution.exitCode()).withFailMessage(execution.output()).isZero();
		assertThat(Files.readString(tempDir.resolve("state"))).isEqualTo("RUNNING\n");
		assertThat(execution.output()).contains("connector monitor state changed: RUNNING");
	}

	@Test
	@DisplayName("task 하나라도 FAILED이면 raw connector payload 없이 health state만 전환한다")
	void redactsFailedConnectorPayload() throws Exception {
		Execution execution = execute("failed", Map.of("CONNECTOR_MONITOR_MAX_CHECKS", "1"));

		assertThat(execution.completed()).isTrue();
		assertThat(execution.exitCode()).isNotZero();
		assertThat(Files.readString(tempDir.resolve("state"))).isEqualTo("NOT_RUNNING\n");
		assertThat(execution.output())
			.contains("connector monitor state changed: NOT_RUNNING")
			.doesNotContain("sensitive-trace", "worker-secret");
	}

	@Test
	@DisplayName("동일 장애는 반복 로그하지 않고 상태 전환만 알린다")
	void logsOnlyStateTransitions() throws Exception {
		Execution execution = execute("failed-failed-running-running", Map.of(
			"CONNECTOR_MONITOR_MAX_CHECKS", "4",
			"CONNECTOR_MONITOR_INTERVAL_SECONDS", "0"
		));

		assertThat(execution.completed()).isTrue();
		assertThat(execution.exitCode()).withFailMessage(execution.output()).isZero();
		assertThat(occurrences(execution.output(), "state changed: NOT_RUNNING")).isEqualTo(1);
		assertThat(occurrences(execution.output(), "state changed: RUNNING")).isEqualTo(1);
	}

	@Test
	@DisplayName("connector 상태 HTTP 호출은 연결 및 전체 전송 시간으로 제한한다")
	void boundsStatusHttpCall() throws Exception {
		Execution execution = execute("hang", Map.of(
			"CONNECTOR_MONITOR_MAX_CHECKS", "1",
			"CONNECTOR_MONITOR_HTTP_CONNECT_TIMEOUT_SECONDS", "1",
			"CONNECTOR_MONITOR_HTTP_MAX_TIME_SECONDS", "1"
		));

		assertThat(execution.completed()).isTrue();
		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.elapsed()).isLessThan(Duration.ofSeconds(4));
		assertThat(Files.readString(tempDir.resolve("curl-invocations.log")))
			.contains("--connect-timeout 1", "--max-time 1");
	}

	private Execution execute(String scenario, Map<String, String> overrides) throws Exception {
		Map<String, String> environment = new HashMap<>();
		environment.put("FAKE_STATE_DIR", tempDir.toString());
		environment.put("FAKE_SCENARIO", scenario);
		environment.put("CURL_BIN", fakeCurl.toString());
		environment.put("CONNECT_URL", "http://connect:8083");
		environment.put("CONNECTOR_NAME", "airbob-outbox-connector");
		environment.put("CONNECTOR_MONITOR_STATE_FILE", tempDir.resolve("state").toString());
		environment.put("CONNECTOR_MONITOR_INTERVAL_SECONDS", "0");
		environment.putAll(overrides);

		ProcessBuilder builder = new ProcessBuilder("/bin/sh", SCRIPT.toString())
			.redirectErrorStream(true);
		builder.environment().putAll(environment);
		long startedAtNanos = System.nanoTime();
		Process process = builder.start();
		boolean completed = process.waitFor(5, TimeUnit.SECONDS);
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

	private int occurrences(String text, String needle) {
		return (text.length() - text.replace(needle, "").length()) / needle.length();
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
		count_file="$FAKE_STATE_DIR/count"
		count=0
		[ ! -f "$count_file" ] || count="$(cat "$count_file")"
		count=$((count + 1))
		printf '%s' "$count" > "$count_file"

		max_time=
		while [ "$#" -gt 0 ]; do
		  case "$1" in
		    --connect-timeout|--max-time)
		      [ "$1" != "--max-time" ] || max_time="$2"
		      shift 2
		      ;;
		    --fail|--silent|--show-error) shift ;;
		    *) shift ;;
		  esac
		done

		case "$FAKE_SCENARIO" in
		  running)
		    printf '%s' '{"connector":{"state":"RUNNING"},"tasks":[{"id":0,"state":"RUNNING"}],"type":"source"}'
		    ;;
		  failed)
		    printf '%s' '{"connector":{"state":"RUNNING","trace":"sensitive-trace"},"tasks":[{"id":0,"state":"RUNNING"},{"id":1,"state":"FAILED","trace":"worker-secret"}],"type":"source"}'
		    ;;
		  failed-failed-running-running)
		    if [ "$count" -le 2 ]; then
		      printf '%s' '{"connector":{"state":"FAILED"},"tasks":[{"id":0,"state":"FAILED"}],"type":"source"}'
		    else
		      printf '%s' '{"connector":{"state":"RUNNING"},"tasks":[{"id":0,"state":"RUNNING"}],"type":"source"}'
		    fi
		    ;;
		  hang)
		    sleep "${max_time:-30}"
		    exit 28
		    ;;
		esac
		""";
}
