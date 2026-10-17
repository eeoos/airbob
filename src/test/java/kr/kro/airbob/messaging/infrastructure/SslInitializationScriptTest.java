package kr.kro.airbob.messaging.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SslInitializationScriptTest {

	private static final String NGINX_ID = "1".repeat(64);
	private static final String CERTBOT_ID = "2".repeat(64);

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("앱 없이 인증서를 발급하고 운영 설정과 다른 컨테이너를 보존한다")
	void issuesCertificateWithoutRewritingProductionConfig() throws Exception {
		Execution result = execute("success");

		assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
		assertThat(result.commands())
			.contains("conf.d/default.conf.init:/etc/nginx/conf.d/default.conf:ro")
			.contains("http://127.0.0.1/health")
			.contains("--email ssl-test@example.invalid")
			.contains("rm -f " + CERTBOT_ID, "rm -f " + NGINX_ID)
			.doesNotContain("stop nginx", "rm -f nginx", "compose up");
		assertThat(result.output()).contains("deploy-oci.sh").doesNotContain("docker-compose.prod.yml");
		String temporaryConfig = Files.readString(Path.of("docker/nginx/conf.d/default.conf.init"));
		assertThat(temporaryConfig).contains("/.well-known/acme-challenge/", "return 404;")
			.doesNotContain("proxy_pass", "app:8080");
	}

	@Test
	@DisplayName("인증서 발급 실패는 원래 실패 코드로 종료하고 두 임시 컨테이너를 정리한다")
	void removesContainersWhenCertificateIssuanceFails() throws Exception {
		Execution result = execute("certificate-fails");

		assertThat(result.exitCode()).withFailMessage(result.output()).isEqualTo(42);
		assertThat(result.commands()).contains("rm -f " + CERTBOT_ID, "rm -f " + NGINX_ID);
		assertThat(result.output()).doesNotContain("인증서 발급 완료");
	}

	@Test
	@DisplayName("포트 충돌로 기동하지 못하면 생성한 컨테이너만 지우고 인증서를 요청하지 않는다")
	void cleansUpAfterNginxStartFails() throws Exception {
		Execution result = execute("port-busy");

		assertThat(result.exitCode()).isEqualTo(43);
		assertThat(result.commands()).contains("rm -f " + NGINX_ID)
			.doesNotContain("certonly", "rm -f " + CERTBOT_ID);
	}

	@Test
	@DisplayName("임시 Nginx가 준비되지 않으면 인증서 요청 전에 종료한다")
	void waitsForNginxBeforeIssuingCertificate() throws Exception {
		Execution result = execute("unhealthy");

		assertThat(result.exitCode()).isNotZero();
		assertThat(result.output()).contains("temporary Nginx did not become ready");
		assertThat(result.commands()).contains("rm -f " + NGINX_ID).doesNotContain("certonly");
	}

	@Test
	@DisplayName("인증서를 발급했더라도 컨테이너 정리에 실패하면 성공으로 종료하지 않는다")
	void reportsCleanupFailure() throws Exception {
		Execution result = execute("cleanup-fails");

		assertThat(result.exitCode()).isNotZero();
		assertThat(result.output()).contains("temporary certificate container cleanup failed");
		assertThat(result.commands()).contains("rm -f " + CERTBOT_ID, "rm -f " + NGINX_ID);
	}

	private Execution execute(String scenario) throws Exception {
		Path project = Files.createDirectories(tempDir.resolve("deployment with spaces"));
		Files.createDirectories(project.resolve("scripts"));
		Files.createDirectories(project.resolve("docker/nginx/conf.d"));
		for (String file : new String[] {"scripts/init-ssl.sh", "docker/nginx/nginx.conf",
			"docker/nginx/conf.d/default.conf", "docker/nginx/conf.d/default.conf.init"}) {
			Files.copy(Path.of(file), project.resolve(file));
		}
		Path productionConfig = project.resolve("docker/nginx/conf.d/default.conf");
		byte[] originalConfig = Files.readAllBytes(productionConfig);
		Path scratch = Files.createDirectory(tempDir.resolve("scratch"));
		Path log = tempDir.resolve("docker.log");
		Path docker = tempDir.resolve("fake-docker");
		Files.writeString(docker, FAKE_DOCKER);
		assertThat(docker.toFile().setExecutable(true)).isTrue();
		ProcessBuilder builder = new ProcessBuilder(
			"bash", project.resolve("scripts/init-ssl.sh").toString(), "ssl-test@example.invalid"
		).directory(tempDir.toFile()).redirectErrorStream(true);
		builder.environment().put("DOCKER_BIN", docker.toString());
		builder.environment().put("FAKE_DOCKER_LOG", log.toString());
		builder.environment().put("FAKE_SCENARIO", scenario);
		builder.environment().put("NGINX_ID", NGINX_ID);
		builder.environment().put("CERTBOT_ID", CERTBOT_ID);
		builder.environment().put("HEALTH_ATTEMPTS", "1");
		builder.environment().put("HEALTH_DELAY_SECONDS", "1");
		builder.environment().put("TMPDIR", scratch.toString());
		Process process = builder.start();
		boolean completed = process.waitFor(5, TimeUnit.SECONDS);
		if (!completed) {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			process.waitFor(1, TimeUnit.SECONDS);
		}
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(completed).withFailMessage(output).isTrue();
		assertThat(Files.readAllBytes(productionConfig)).isEqualTo(originalConfig);
		try (var remaining = Files.list(scratch)) {
			assertThat(remaining.toList()).isEmpty();
		}
		return new Execution(process.exitValue(), output, Files.readString(log));
	}

	private record Execution(int exitCode, String output, String commands) {
	}

	private static final String FAKE_DOCKER = """
		#!/bin/sh
		set -eu
		printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
		command=$1
		case "$command" in
		  create|run)
		    while [ "$1" != --cidfile ]; do shift; done
		    if [ "$command" = create ]; then
		      printf '%s\n' "$NGINX_ID" > "$2"
		    else
		      printf '%s\n' "$CERTBOT_ID" > "$2"
		      [ "$FAKE_SCENARIO" != certificate-fails ] || exit 42
		    fi ;;
		  start) [ "$FAKE_SCENARIO" != port-busy ] || exit 43 ;;
		  exec) [ "$FAKE_SCENARIO" != unhealthy ] || exit 1 ;;
		  rm) [ "$FAKE_SCENARIO" != cleanup-fails ] || exit 44 ;;
		esac
		exit 0
		""";
}
