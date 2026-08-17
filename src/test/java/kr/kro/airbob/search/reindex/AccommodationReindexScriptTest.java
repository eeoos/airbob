package kr.kro.airbob.search.reindex;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccommodationReindexScriptTest {

	private static final String TARGET_INDEX = "accommodations-v20260816010101";
	private static final Path SCRIPT = Path.of("scripts/reindex-accommodations.sh").toAbsolutePath();

	@TempDir
	Path tempDir;

	private Path fakeCurl;
	private Path fakeDocker;
	private Path fakeJq;

	@BeforeEach
	void setUp() throws IOException {
		fakeCurl = executable("fake-curl", FAKE_CURL);
		fakeDocker = executable("fake-docker", FAKE_DOCKER);
		fakeJq = executable("fake-jq", FAKE_JQ);
		Files.writeString(tempDir.resolve("compose.yml"), "services: {}\n");
	}

	@Test
	@DisplayName("검증을 마친 뒤에만 기존 인덱스에서 새 버전 인덱스로 alias를 원자 전환한다")
	void switchesAliasOnlyAfterValidation() throws Exception {
		Execution execution = execute("existing", true);

		assertThat(execution.exitCode()).withFailMessage(execution.output()).isZero();
		String requests = Files.readString(tempDir.resolve("requests.log"));
		String docker = Files.readString(tempDir.resolve("docker.log"));
		assertThat(requests.indexOf("PUT /" + TARGET_INDEX))
			.isLessThan(requests.indexOf("POST /" + TARGET_INDEX + "/_refresh"));
		assertThat(requests.indexOf("GET /" + TARGET_INDEX + "/_count"))
			.isLessThan(requests.indexOf("POST /_aliases"));
		assertThat(requests)
			.contains("\"remove\":{\"index\":\"accommodations-v20260815010101\",\"alias\":\"accommodations\",\"must_exist\":true}")
			.contains("\"add\":{\"index\":\"" + TARGET_INDEX
				+ "\",\"alias\":\"accommodations\",\"is_write_index\":true}");
		assertThat(docker)
			.contains("exec -T mysql mysql --defaults-extra-file=/dev/stdin")
			.contains("run --rm -e LOGSTASH_TARGET_INDEX=" + TARGET_INDEX + " logstash")
			.doesNotContain("top-secret-password");
		assertThat(Files.readString(tempDir.resolve("curl-invocations.log")))
			.contains("--connect-timeout 5")
			.contains("--max-time 30")
			.doesNotContain("top-secret-password");
	}

	@Test
	@DisplayName("MySQL과 새 인덱스 건수가 다르면 기존 alias를 유지한다")
	void keepsAliasWhenDocumentCountDoesNotMatch() throws Exception {
		Execution execution = execute("count-mismatch", true);

		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.output()).contains("document count mismatch");
		assertThat(Files.readString(tempDir.resolve("requests.log")))
			.doesNotContain("POST /_aliases");
	}

	@Test
	@DisplayName("Logstash 적재가 실패하면 기존 alias를 유지한다")
	void keepsAliasWhenLogstashFails() throws Exception {
		Execution execution = execute("logstash-failure", true);

		assertThat(execution.exitCode()).isNotZero();
		assertThat(Files.readString(tempDir.resolve("requests.log")))
			.doesNotContain("POST /_aliases");
	}

	@Test
	@DisplayName("재색인 중 공개 숙소 수가 바뀌면 기존 alias를 유지한다")
	void keepsAliasWhenPublishedCountChanges() throws Exception {
		Execution execution = execute("published-count-changed", true);

		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.output()).contains("published accommodation count changed");
		assertThat(Files.readString(tempDir.resolve("requests.log")))
			.doesNotContain("POST /_aliases");
	}

	@Test
	@DisplayName("Elasticsearch가 alias 전환 작업을 거부하면 성공으로 처리하지 않는다")
	void rejectsAliasActionErrors() throws Exception {
		Execution execution = execute("alias-action-error", true);

		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.output()).contains("switch alias was not acknowledged");
		assertThat(tempDir.resolve("switched")).doesNotExist();
	}

	@Test
	@DisplayName("동시에 다른 재색인이 alias를 바꾸면 오래된 작업은 전환하지 않는다")
	void rejectsConcurrentAliasChange() throws Exception {
		Execution execution = execute("concurrent-cutover", true);

		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.output()).contains("alias changed while reindexing");
		assertThat(Files.readString(tempDir.resolve("requests.log")))
			.doesNotContain("POST /_aliases");
	}

	@Test
	@DisplayName("같은 이름의 legacy 물리 인덱스가 있으면 파괴하지 않고 중단한다")
	void rejectsLegacyConcreteIndex() throws Exception {
		Execution execution = execute("legacy", true);

		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.output()).contains("concrete index named accommodations");
		assertThat(Files.readString(tempDir.resolve("requests.log")))
			.doesNotContain("PUT /" + TARGET_INDEX);
		assertThat(tempDir.resolve("docker.log")).doesNotExist();
	}

	@Test
	@DisplayName("대상 인덱스 확인이 실패하면 이미 존재한다고 오판하지 않고 중단한다")
	void rejectsTargetInspectionFailure() throws Exception {
		Execution execution = execute("target-inspection-error", true);

		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.output()).contains("could not inspect target index").contains("HTTP 500");
		assertThat(tempDir.resolve("docker.log")).doesNotExist();
	}

	@Test
	@DisplayName("색인 consumer 중지 확인 없이는 외부 상태를 조회하거나 변경하지 않는다")
	void requiresIndexingConsumerPauseConfirmation() throws Exception {
		Execution execution = execute("existing", false);

		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.output()).contains("CONFIRM_INDEXING_CONSUMER_PAUSED=true");
		assertThat(tempDir.resolve("requests.log")).doesNotExist();
		assertThat(tempDir.resolve("docker.log")).doesNotExist();
	}

	@Test
	@DisplayName("개행이 포함된 자격증명은 설정 파일을 만들거나 외부 요청을 보내기 전에 거부한다")
	void rejectsCredentialNewlines() throws Exception {
		Execution execution = execute(
			"existing",
			true,
			Map.of("ELASTICSEARCH_PASSWORD", "secret\ninjected-option")
		);

		assertThat(execution.exitCode()).isNotZero();
		assertThat(execution.output()).contains("credentials must not contain newlines");
		assertThat(tempDir.resolve("requests.log")).doesNotExist();
		assertThat(tempDir.resolve("docker.log")).doesNotExist();
	}

	@Test
	@DisplayName("최초 구축은 검증된 버전 인덱스에 alias를 생성한다")
	void bootstrapsAliasWhenNoIndexExists() throws Exception {
		Execution execution = execute("bootstrap", true);

		assertThat(execution.exitCode()).withFailMessage(execution.output()).isZero();
		String requests = Files.readString(tempDir.resolve("requests.log"));
		assertThat(requests).contains("POST /_aliases");
		String aliasRequest = requests.substring(requests.indexOf("POST /_aliases"));
		assertThat(aliasRequest)
			.contains("\"add\":{\"index\":\"" + TARGET_INDEX + "\"")
			.doesNotContain("\"remove\"");
	}

	private Execution execute(String scenario, boolean confirmPaused) throws Exception {
		return execute(scenario, confirmPaused, Map.of());
	}

	private Execution execute(
		String scenario,
		boolean confirmPaused,
		Map<String, String> environmentOverrides
	) throws Exception {
		Map<String, String> environment = new HashMap<>();
		environment.put("FAKE_STATE_DIR", tempDir.toString());
		environment.put("FAKE_SCENARIO", scenario);
		environment.put("FAKE_ES_URL", "http://elasticsearch:9200");
		environment.put("CURL_BIN", fakeCurl.toString());
		environment.put("DOCKER_BIN", fakeDocker.toString());
		environment.put("JQ_BIN", fakeJq.toString());
		environment.put("ELASTICSEARCH_URL", "http://elasticsearch:9200");
		environment.put("ES_TARGET_INDEX", TARGET_INDEX);
		environment.put("COMPOSE_FILE", tempDir.resolve("compose.yml").toString());
		environment.put("LOGSTASH_JDBC_USER", "readonly-operator");
		environment.put("LOGSTASH_JDBC_PASSWORD", "top-secret-password");
		environment.put("ELASTICSEARCH_USERNAME", "search-operator");
		environment.put("ELASTICSEARCH_PASSWORD", "top-secret-password");
		if (confirmPaused) {
			environment.put("CONFIRM_INDEXING_CONSUMER_PAUSED", "true");
		}
		environment.putAll(environmentOverrides);

		ProcessBuilder builder = new ProcessBuilder("/bin/bash", SCRIPT.toString())
			.redirectErrorStream(true);
		builder.environment().putAll(environment);
		Process process = builder.start();
		boolean completed = process.waitFor(10, TimeUnit.SECONDS);
		assertThat(completed).isTrue();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new Execution(process.exitValue(), output);
	}

	private Path executable(String name, String contents) throws IOException {
		Path path = tempDir.resolve(name);
		Files.writeString(path, contents);
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
		return path;
	}

	private record Execution(int exitCode, String output) {
	}

	private static final String FAKE_CURL = """
		#!/bin/bash
		set -euo pipefail
		printf '%s\n' "$*" >> "$FAKE_STATE_DIR/curl-invocations.log"
		method=GET
		output_file=
		data=
		url=
		while (($#)); do
		  case "$1" in
		    -X) method="$2"; shift 2 ;;
		    -o) output_file="$2"; shift 2 ;;
		    -w) shift 2 ;;
		    -H|-u|--config|--connect-timeout|--max-time) shift 2 ;;
		    --data-binary) data="$2"; shift 2 ;;
		    -s|-S|-sS|-f|-fsS) shift ;;
		    *) url="$1"; shift ;;
		  esac
		done
		path="${url#${FAKE_ES_URL}}"
		request_data="$data"
		if [[ "$data" == @* ]]; then
		  request_data="$(tr -d '\n\t ' < "${data#@}")"
		else
		  request_data="$(printf '%s' "$data" | tr -d '\n\t ')"
		fi
		printf '%s %s %s\n' "$method" "$path" "$request_data" >> "$FAKE_STATE_DIR/requests.log"
		status=200
		response='{"acknowledged":true}'
		case "$method $path" in
		  "GET /_alias/accommodations")
		    alias_calls_file="$FAKE_STATE_DIR/alias-calls"
		    alias_calls=0
		    [[ -f "$alias_calls_file" ]] && alias_calls="$(cat "$alias_calls_file")"
		    alias_calls=$((alias_calls + 1))
		    printf '%s' "$alias_calls" > "$alias_calls_file"
		    if [[ -f "$FAKE_STATE_DIR/switched" ]]; then
		      response='{"'"${ES_TARGET_INDEX}"'":{"aliases":{"accommodations":{"is_write_index":true}}}}'
		    elif [[ "$FAKE_SCENARIO" == bootstrap || "$FAKE_SCENARIO" == legacy ]]; then
		      status=404; response='{}'
		    elif [[ "$FAKE_SCENARIO" == concurrent-cutover && "$alias_calls" -gt 1 ]]; then
		      response='{"accommodations-v20260814010101":{"aliases":{"accommodations":{"is_write_index":true}}}}'
		    else
		      response='{"accommodations-v20260815010101":{"aliases":{"accommodations":{"is_write_index":true}}}}'
		    fi
		    ;;
		  "HEAD /accommodations")
		    [[ "$FAKE_SCENARIO" == legacy ]] || status=404
		    response=''
		    ;;
		  "HEAD /${ES_TARGET_INDEX}")
		    if [[ "$FAKE_SCENARIO" == target-inspection-error ]]; then status=500; else status=404; fi
		    response=''
		    ;;
		  "GET /${ES_TARGET_INDEX}/_count")
		    if [[ "$FAKE_SCENARIO" == count-mismatch ]]; then response='{"count":1}'; else response='{"count":2}'; fi
		    ;;
		  "POST /_aliases")
		    if [[ "$FAKE_SCENARIO" == alias-action-error ]]; then
		      response='{"acknowledged":true,"errors":true}'
		    else
		      touch "$FAKE_STATE_DIR/switched"
		    fi
		    ;;
		  "GET /accommodations/_count") response='{"count":2}' ;;
		esac
		printf '%s' "$response" > "$output_file"
		printf '%s' "$status"
		""";

	private static final String FAKE_DOCKER = """
		#!/bin/bash
		set -euo pipefail
		printf '%s\n' "$*" >> "$FAKE_STATE_DIR/docker.log"
		if [[ " $* " == *" exec "* ]]; then
		  count_calls_file="$FAKE_STATE_DIR/count-calls"
		  count_calls=0
		  [[ -f "$count_calls_file" ]] && count_calls="$(cat "$count_calls_file")"
		  count_calls=$((count_calls + 1))
		  printf '%s' "$count_calls" > "$count_calls_file"
		  if [[ "$FAKE_SCENARIO" == published-count-changed && "$count_calls" -gt 1 ]]; then
		    printf '3\n'
		  else
		    printf '2\n'
		  fi
		elif [[ "$FAKE_SCENARIO" == logstash-failure ]]; then
		  exit 42
		fi
		""";

	private static final String FAKE_JQ = """
		#!/bin/bash
		set -euo pipefail
		old=
		target=
		alias=
		expression=
		while (($#)); do
		  case "$1" in
		    -e|-r|-c|-n|-cn) shift ;;
		    --arg)
		      case "$2" in
		        old) old="$3" ;;
		        target) target="$3" ;;
		        alias) alias="$3" ;;
		      esac
		      shift 3
		      ;;
		    *) expression="$1"; shift ;;
		  esac
		done
		if [[ "$expression" == *"actions"* ]]; then
		  if [[ -n "$old" ]]; then
		    printf '{"actions":[{"remove":{"index":"%s","alias":"%s","must_exist":true}},{"add":{"index":"%s","alias":"%s","is_write_index":true}}]}' "$old" "$alias" "$target" "$alias"
		  else
		    printf '{"actions":[{"add":{"index":"%s","alias":"%s","is_write_index":true}}]}' "$target" "$alias"
		  fi
		  exit 0
		fi
		input="$(cat)"
		case "$expression" in
		  "keys[]") printf '%s' "$input" | cut -d '"' -f 2 ;;
		  ".count") printf '%s' "$input" | tr -cd '0-9' ;;
		  *"acknowledged"*)
		    [[ "$input" == *'"acknowledged":true'* && "$input" != *'"errors":true'* ]]
		    ;;
		  *"is_write_index"*)
		    [[ "$input" == *"\"$target\""* && "$input" == *'"is_write_index":true'* ]]
		    ;;
		  *) exit 1 ;;
		esac
		""";
}
