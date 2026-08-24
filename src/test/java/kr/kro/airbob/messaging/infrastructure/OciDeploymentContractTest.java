package kr.kro.airbob.messaging.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OciDeploymentContractTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("CD는 checkout한 OCI 배포 자산을 동기화한 뒤 저장소의 배포 스크립트를 실행한다")
	void deploysReviewedOciAssets() throws IOException {
		String workflow = read(".github/workflows/cd.yml");
		String deployJob = workflow.substring(workflow.indexOf("  deploy-oci:"));

		assertThat(deployJob)
			.contains("uses: actions/checkout@v4")
			.contains("sh scripts/sync-oci-deployment-assets.sh")
			.contains("sh \"$HOME/airbob/scripts/deploy-oci.sh\"")
			.doesNotContain("-f docker-compose.yml")
			.doesNotContain("OLD_IMAGE_ID", "rollback()");
	}

	@Test
	@DisplayName("동기화 대상은 reviewed asset으로 닫고 서버 secret과 생성 데이터는 건드리지 않는다")
	void preservesServerOwnedDeploymentState() throws IOException {
		String script = read("scripts/sync-oci-deployment-assets.sh");
		List<String> managedAssetLines = script.lines()
			.map(String::trim)
			.toList();

		assertThat(script)
			.contains("docker-compose.oci.yml")
			.contains("src/main/resources/db/migration")
			.contains("debezium-config")
			.contains("docker/debezium")
			.contains("docker/kafka")
			.contains("docker/mysql/init")
			.contains("monitoring")
			.contains("nginx")
			.contains("scripts")
			.contains("--delete");
		assertThat(managedAssetLines)
			.doesNotContain(".env.oci", "logs", "certbot-certs", "certbot-www");
	}

	@Test
	@DisplayName("동기화 실행은 managed asset drift만 지우고 서버 소유 파일을 보존한다")
	void synchronizesOnlyManagedDeploymentAssets() throws Exception {
		Path source = tempDir.resolve("source");
		Path target = tempDir.resolve("target");
		Files.createDirectories(source);
		Files.createDirectories(target);
		Files.writeString(source.resolve("docker-compose.oci.yml"), "services: {}\n");
		for (String directory : List.of(
			"src/main/resources/db/migration",
			"debezium-config",
			"docker/debezium",
			"docker/kafka",
			"docker/mysql/init",
			"logstash",
			"monitoring",
			"nginx",
			"scripts"
		)) {
			Path assetDirectory = source.resolve(directory);
			Files.createDirectories(assetDirectory);
			Files.writeString(assetDirectory.resolve("reviewed"), directory);
		}

		Files.writeString(target.resolve(".env.oci"), "SERVER_SECRET=preserve\n");
		Files.createDirectories(target.resolve("logs"));
		Files.writeString(target.resolve("logs/app.log"), "preserve\n");
		Files.createDirectories(target.resolve("generated-certs"));
		Files.writeString(target.resolve("generated-certs/cert.pem"), "preserve\n");
		Files.createDirectories(target.resolve("scripts"));
		Files.writeString(target.resolve("scripts/stale"), "delete\n");

		Process process = new ProcessBuilder(
			"/bin/sh",
			Path.of("scripts/sync-oci-deployment-assets.sh").toAbsolutePath().toString(),
			source.toString(),
			target.toString()
		).redirectErrorStream(true).start();
		boolean completed = process.waitFor(5, TimeUnit.SECONDS);
		if (!completed) {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
			process.waitFor(1, TimeUnit.SECONDS);
		}
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertThat(completed).withFailMessage(output).isTrue();
		assertThat(process.exitValue()).withFailMessage(output).isZero();
		assertThat(target.resolve("scripts/reviewed")).hasContent("scripts");
		assertThat(target.resolve("scripts/stale")).doesNotExist();
		assertThat(target.resolve(".env.oci")).hasContent("SERVER_SECRET=preserve");
		assertThat(target.resolve("logs/app.log")).hasContent("preserve");
		assertThat(target.resolve("generated-certs/cert.pem")).hasContent("preserve");
	}

	@Test
	@DisplayName("OCI 배포는 config와 메시징 bootstrap을 앱보다 먼저 검증한다")
	void gatesAppOnExplicitMessagingBootstrap() throws IOException {
		String script = read("scripts/deploy-oci.sh");

		assertThat(script)
			.contains("--env-file \"$ENV_FILE\" -f \"$COMPOSE_FILE\"")
			.contains("COMPOSE_FILE=\"${COMPOSE_FILE:-$DEPLOY_DIR/docker-compose.oci.yml}\"")
			.contains("compose config --quiet")
			.contains("compose stop nginx app")
			.contains("compose run --rm --no-deps kafka-topic-init")
			.contains("compose run --rm --no-deps flyway-migrate")
			.contains("compose up -d --no-deps --force-recreate debezium")
			.contains("compose run --rm --no-deps debezium-connector-init")
			.contains("debezium-connector-monitor")
			.contains("compose up -d --no-deps --force-recreate --pull never app")
			.doesNotContain("OLD_IMAGE_ID", "rollback()", "Rolling back", "docker image tag \"$OLD");

		int admissionBoundary = script.indexOf("if ! compose stop nginx app");
		assertThat(script.indexOf("compose config --quiet"))
			.isLessThan(admissionBoundary);
		assertThat(admissionBoundary)
			.isLessThan(script.indexOf("compose run --rm --no-deps kafka-topic-init"));
		assertThat(script.indexOf("compose run --rm --no-deps kafka-topic-init"))
			.isLessThan(script.indexOf("compose run --rm --no-deps flyway-migrate"));
		assertThat(script.indexOf("compose run --rm --no-deps flyway-migrate"))
			.isLessThan(script.indexOf("compose run --rm --no-deps debezium-connector-init"));
		assertThat(script.indexOf("compose run --rm --no-deps debezium-connector-init"))
			.isLessThan(script.indexOf("compose up -d --no-deps --force-recreate --pull never app"));
	}

	@Test
	@DisplayName("앱 시작 이후 실패는 ingress를 닫고 V18 호환 binary의 roll-forward만 허용한다")
	void stopsAdmissionAcrossIrreversibleMigrationBoundary() throws IOException {
		String script = read("scripts/deploy-oci.sh");

		assertThat(script)
			.contains("compose stop nginx app")
			.contains("V18-compatible roll-forward is required")
			.contains("No pre-V18 binary rollback was attempted")
			.doesNotContain("Rolling back to image");
	}

	@Test
	@DisplayName("Flyway 이후 connector bootstrap 실패도 ingress와 app을 닫은 채 roll-forward를 요구한다")
	void keepsAdmissionClosedWhenConnectorBootstrapFailsAfterMigration() throws Exception {
		Path deployment = tempDir.resolve("deployment");
		Files.createDirectories(deployment);
		Files.writeString(deployment.resolve(".env.oci"), "DB_ROOT_PASSWORD=test\n");
		Files.writeString(deployment.resolve("docker-compose.oci.yml"), "services: {}\n");
		Path dockerLog = tempDir.resolve("docker.log");
		Path fakeDocker = tempDir.resolve("docker");
		Files.writeString(fakeDocker, """
			#!/bin/sh
			printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
			case "$*" in
			  *"up -d --no-deps --force-recreate debezium"*) exit 42 ;;
			esac
			exit 0
			""");
		assertThat(fakeDocker.toFile().setExecutable(true)).isTrue();

		ProcessBuilder processBuilder = new ProcessBuilder(
			"/bin/sh",
			Path.of("scripts/deploy-oci.sh").toAbsolutePath().toString()
		).redirectErrorStream(true);
		processBuilder.environment().put("DEPLOY_DIR", deployment.toString());
		processBuilder.environment().put("DOCKER_BIN", fakeDocker.toString());
		processBuilder.environment().put("IMAGE_TAG", "reviewed-sha");
		processBuilder.environment().put("FAKE_DOCKER_LOG", dockerLog.toString());
		Process process = processBuilder.start();
		boolean completed = process.waitFor(5, TimeUnit.SECONDS);
		if (!completed) {
			process.destroyForcibly();
			process.waitFor(1, TimeUnit.SECONDS);
		}
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String commands = Files.readString(dockerLog);

		assertThat(completed).withFailMessage(output).isTrue();
		assertThat(process.exitValue()).isNotZero();
		assertThat(commands)
			.contains("run --rm --no-deps flyway-migrate")
			.contains("up -d --no-deps --force-recreate debezium");
		assertThat(commands.lastIndexOf("stop nginx app"))
			.isGreaterThan(commands.indexOf("up -d --no-deps --force-recreate debezium"));
		assertThat(commands).doesNotContain("--force-recreate --pull never app");
		assertThat(output).contains("V18-compatible roll-forward is required");
	}

	@Test
	@DisplayName("OCI compose는 app과 connector보다 먼저 동일 버전 Flyway migration을 실행한다")
	void migratesSchemaBeforeRegisteringConnector() throws IOException {
		String compose = read("docker-compose.oci.yml");
		String migration = serviceBlock(compose, "flyway-migrate");
		String connectorInit = serviceBlock(compose, "debezium-connector-init");

		assertThat(migration)
			.contains("flyway/flyway:11.7.2")
			.contains("./src/main/resources/db/migration:/flyway/sql:ro")
			.contains("FLYWAY_BASELINE_ON_MIGRATE: \"true\"")
			.contains("condition: service_healthy")
			.contains("restart: \"no\"");
		assertThat(connectorInit)
			.contains("flyway-migrate:")
			.contains("condition: service_completed_successfully");
	}

	@Test
	@DisplayName("OCI compose는 connector 상태 monitor를 bootstrap gate이자 지속 health 신호로 둔다")
	void continuouslyMonitorsConnectorAndTasks() throws IOException {
		String compose = read("docker-compose.oci.yml");
		String debezium = serviceBlock(compose, "debezium");
		String monitor = serviceBlock(compose, "debezium-connector-monitor");
		String app = serviceBlock(compose, "app");

		assertThat(debezium)
			.contains("./docker/debezium/connect-distributed.properties:"
				+ "/opt/kafka/config/connect-distributed.properties:ro");
		assertThat(monitor)
			.contains("docker/debezium/monitor-connector.sh")
			.contains("condition: service_completed_successfully")
			.contains("restart: unless-stopped")
			.contains("healthcheck:")
			.contains("CONNECTOR_MONITOR_STATE_FILE");
		assertThat(app)
			.contains("debezium-connector-monitor:")
			.contains("condition: service_healthy");
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
