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
			.contains("uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262")
			.contains("persist-credentials: false")
			.contains("sh scripts/sync-oci-deployment-assets.sh")
			.contains("sh \"$HOME/airbob/scripts/deploy-oci.sh\"")
			.contains("if: always()", "docker logout ghcr.io")
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
			.contains("compose run --rm --no-deps reservation-inventory-cutover-preflight")
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
			.isLessThan(script.indexOf("compose run --rm --no-deps reservation-inventory-cutover-preflight"));
		assertThat(script.indexOf("compose run --rm --no-deps reservation-inventory-cutover-preflight"))
			.isLessThan(script.indexOf("compose run --rm --no-deps kafka-topic-init"));
		assertThat(script.indexOf("compose run --rm --no-deps kafka-topic-init"))
			.isLessThan(script.indexOf("compose run --rm --no-deps flyway-migrate"));
		assertThat(script.indexOf("compose run --rm --no-deps flyway-migrate"))
			.isLessThan(script.indexOf("compose run --rm --no-deps debezium-connector-init"));
		assertThat(script.indexOf("compose run --rm --no-deps debezium-connector-init"))
			.isLessThan(script.indexOf("compose up -d --no-deps --force-recreate --pull never app"));
	}

	@Test
	@DisplayName("V25 컷오버 이후 실패는 ingress를 닫고 현재 inventory binary의 roll-forward만 허용한다")
	void stopsAdmissionAcrossIrreversibleMigrationBoundary() throws IOException {
		String script = read("scripts/deploy-oci.sh");

		assertThat(script)
			.contains("compose stop nginx app")
			.contains("Reservation inventory cutover preflight failed before Flyway; no migration was attempted")
			.contains("No automatic V24 restart was attempted")
			.contains("current binary that understands the V25 cutover, V26 inventory, and V27 index")
			.contains("No pre-V25 binary rollback was attempted")
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
		assertThat(output)
			.contains("current binary that understands the V25 cutover, V26 inventory, and V27 index");
	}

	@Test
	@DisplayName("실행 중인 app의 bootstrap health가 unhealthy여도 전체 readiness 기한 동안 회복을 기다린다")
	void waitsForRunningApplicationToRecoverFromUnhealthyReadiness() throws Exception {
		Path deployment = tempDir.resolve("readiness-deployment");
		Path fakeBin = tempDir.resolve("readiness-bin");
		Files.createDirectories(deployment);
		Files.createDirectories(fakeBin);
		Files.writeString(deployment.resolve(".env.oci"), "DB_ROOT_PASSWORD=test\n");
		Files.writeString(deployment.resolve("docker-compose.oci.yml"), "services: {}\n");
		Path dockerLog = tempDir.resolve("readiness-docker.log");
		Path appInspectCount = tempDir.resolve("app-inspect-count");
		Path fakeDocker = fakeBin.resolve("docker");
		Files.writeString(fakeDocker, """
			#!/bin/sh
			printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
			case "$*" in
			  *"airbob-debezium-connector-monitor"*) printf '%s\n' 'running|healthy' ;;
			  *"inspect "*" debezium"*) printf '%s\n' 'running|healthy' ;;
			  *"airbob-app"*)
			    count=0
			    if [ -r "$FAKE_APP_INSPECT_COUNT" ]; then
			      count=$(sed -n '1p' "$FAKE_APP_INSPECT_COUNT")
			    fi
			    count=$((count + 1))
			    printf '%s\n' "$count" > "$FAKE_APP_INSPECT_COUNT"
			    if [ "$count" -lt 3 ]; then
			      printf '%s\n' 'running|unhealthy'
			    else
			      printf '%s\n' 'running|healthy'
			    fi
			    ;;
			  *"inspect "*" nginx"*) printf '%s\n' 'running' ;;
			esac
			exit 0
			""");
		Path fakeSleep = fakeBin.resolve("sleep");
		Files.writeString(fakeSleep, "#!/bin/sh\nexit 0\n");
		assertThat(fakeDocker.toFile().setExecutable(true)).isTrue();
		assertThat(fakeSleep.toFile().setExecutable(true)).isTrue();

		ProcessBuilder processBuilder = new ProcessBuilder(
			"/bin/sh",
			Path.of("scripts/deploy-oci.sh").toAbsolutePath().toString()
		).redirectErrorStream(true);
		processBuilder.environment().put("DEPLOY_DIR", deployment.toString());
		processBuilder.environment().put("DOCKER_BIN", fakeDocker.toString());
		processBuilder.environment().put("IMAGE_TAG", "reviewed-sha");
		processBuilder.environment().put("FAKE_DOCKER_LOG", dockerLog.toString());
		processBuilder.environment().put("FAKE_APP_INSPECT_COUNT", appInspectCount.toString());
		processBuilder.environment().put("HEALTH_ATTEMPTS", "5");
		processBuilder.environment().put("HEALTH_DELAY_SECONDS", "1");
		processBuilder.environment().put(
			"PATH", fakeBin + ":" + processBuilder.environment().get("PATH"));

		Process process = processBuilder.start();
		boolean completed = process.waitFor(5, TimeUnit.SECONDS);
		if (!completed) {
			process.destroyForcibly();
			process.waitFor(1, TimeUnit.SECONDS);
		}
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertThat(completed).withFailMessage(output).isTrue();
		assertThat(process.exitValue()).withFailMessage(output).isZero();
		assertThat(Files.readString(appInspectCount).trim()).isEqualTo("3");
		assertThat(output)
			.contains("application health: state=running, probe=unhealthy")
			.contains("application health: state=running, probe=healthy")
			.contains("OCI deployment is healthy");
		assertThat(Files.readString(dockerLog))
			.contains("up -d --no-deps --force-recreate nginx");
	}

	@Test
	@DisplayName("health 대기는 unhealthy 자체가 아니라 exited 또는 dead container만 즉시 실패한다")
	void treatsOnlyTerminalContainerStatesAsImmediateHealthFailure() throws IOException {
		String script = read("scripts/deploy-oci.sh");

		assertThat(script)
			.contains("exited|dead) return 1")
			.contains("case \"$container_health\" in")
			.doesNotContain("unhealthy|exited|dead");
	}

	@Test
	@DisplayName("OCI compose는 app과 connector보다 먼저 동일 버전 Flyway migration을 실행한다")
	void migratesSchemaBeforeRegisteringConnector() throws IOException {
		String compose = read("docker-compose.oci.yml");
		String migration = serviceBlock(compose, "flyway-migrate");
		String inventoryPreflight = serviceBlock(compose, "reservation-inventory-cutover-preflight");
		String connectorInit = serviceBlock(compose, "debezium-connector-init");

		assertThat(migration)
			.contains("flyway/flyway:11.7.2")
			.contains("./src/main/resources/db/migration:/flyway/sql:ro")
			.contains("FLYWAY_BASELINE_ON_MIGRATE: \"true\"")
			.contains("condition: service_healthy")
			.contains("restart: \"no\"");
		assertThat(inventoryPreflight)
			.contains("mysql:8.0.33")
			.contains("./scripts/preflight-reservation-inventory-cutover.sh:/preflight.sh:ro")
			.contains("entrypoint: [\"/bin/sh\", \"/preflight.sh\"]")
			.contains("restart: \"no\"");
		assertThat(connectorInit)
			.contains("flyway-migrate:")
			.contains("condition: service_completed_successfully");
	}

	@Test
	@DisplayName("V25 preflight는 이전 writer 정지 뒤 기존 예약이 있으면 Flyway 전에 거부한다")
	void rejectsNonEmptyReservationInventoryCutover() throws Exception {
		Path fakeBin = tempDir.resolve("preflight-bin");
		Files.createDirectories(fakeBin);
		Path fakeMysql = fakeBin.resolve("mysql");
		Files.writeString(fakeMysql, """
			#!/bin/sh
			printf '%s\n' "$*" >> "${FAKE_MYSQL_LOG:?}"
			case "$*" in
			  *"TABLE_NAME = 'accommodation_inventory_day'"*) printf '%s\n' "${FAKE_INVENTORY_TABLE_COUNT:?}" ;;
			  *"TABLE_NAME = 'reservation'"*) printf '%s\n' 1 ;;
			  *"SELECT COUNT(*) FROM reservation"*) printf '%s\n' "${FAKE_RESERVATION_COUNT:?}" ;;
			  *"TABLE_NAME = 'accommodation'"*"TABLE_TYPE = 'BASE TABLE'"*) printf '%s\n' 1 ;;
			  *"COLUMN_NAME = 'time_zone_id'"*) printf '%s\n' 1 ;;
			  *"FROM accommodation"*) printf '%s\n' 0 ;;
			  *) exit 91 ;;
			esac
			""");
		assertThat(fakeMysql.toFile().setExecutable(true)).isTrue();

		ProcessBuilder processBuilder = new ProcessBuilder(
			"/bin/sh",
			Path.of("scripts/preflight-reservation-inventory-cutover.sh").toAbsolutePath().toString()
		).redirectErrorStream(true);
		processBuilder.environment().put("PATH", fakeBin + ":" + System.getenv("PATH"));
		processBuilder.environment().put("MYSQL_USER", "airbob");
		processBuilder.environment().put("MYSQL_PASSWORD", "not-logged-test-password");
		processBuilder.environment().put("FAKE_MYSQL_LOG", tempDir.resolve("preflight-reject.log").toString());
		processBuilder.environment().put("FAKE_INVENTORY_TABLE_COUNT", "0");
		processBuilder.environment().put("FAKE_RESERVATION_COUNT", "1");

		Process process = processBuilder.start();
		assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertThat(process.exitValue()).isNotZero();
		assertThat(output)
			.contains("reservation must contain zero rows before V25")
			.doesNotContain("not-logged-test-password");
	}

	@Test
	@DisplayName("V26 inventory가 이미 있으면 정상 예약을 보존하고 timezone 검사만 계속한다")
	void permitsSubsequentDeploymentsWithReservations() throws Exception {
		Path fakeBin = tempDir.resolve("post-cutover-bin");
		Files.createDirectories(fakeBin);
		Path mysqlLog = tempDir.resolve("post-cutover-mysql.log");
		Path fakeMysql = fakeBin.resolve("mysql");
		Files.writeString(fakeMysql, """
			#!/bin/sh
			printf '%s\n' "$*" >> "${FAKE_MYSQL_LOG:?}"
			case "$*" in
			  *"TABLE_NAME = 'accommodation_inventory_day'"*) printf '%s\n' 1 ;;
			  *"TABLE_NAME = 'reservation'"*) printf '%s\n' 1 ;;
			  *"SELECT COUNT(*) FROM reservation"*) printf '%s\n' 8 ;;
			  *"TABLE_NAME = 'accommodation'"*"TABLE_TYPE = 'BASE TABLE'"*) printf '%s\n' 1 ;;
			  *"COLUMN_NAME = 'time_zone_id'"*) printf '%s\n' 1 ;;
			  *"FROM accommodation"*) printf '%s\n' 0 ;;
			  *) exit 91 ;;
			esac
			""");
		assertThat(fakeMysql.toFile().setExecutable(true)).isTrue();

		ProcessBuilder processBuilder = new ProcessBuilder(
			"/bin/sh",
			Path.of("scripts/preflight-reservation-inventory-cutover.sh").toAbsolutePath().toString()
		).redirectErrorStream(true);
		processBuilder.environment().put("PATH", fakeBin + ":" + System.getenv("PATH"));
		processBuilder.environment().put("MYSQL_USER", "airbob");
		processBuilder.environment().put("MYSQL_PASSWORD", "not-logged-test-password");
		processBuilder.environment().put("FAKE_MYSQL_LOG", mysqlLog.toString());

		Process process = processBuilder.start();
		assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertThat(process.exitValue()).withFailMessage(output).isZero();
		assertThat(output).contains("reservation inventory cutover preflight passed");
		assertThat(Files.readString(mysqlLog))
			.contains("TABLE_NAME = 'accommodation_inventory_day'")
			.doesNotContain("SELECT COUNT(*) FROM reservation;");
	}

	@Test
	@DisplayName("timezone preflight는 CET 같은 유효한 single-segment ZoneId 형식을 허용한다")
	void acceptsPlausibleSingleSegmentZoneIdsBeforeJavaValidation() throws IOException {
		String plausibleZoneIdPattern =
			"time_zone_id NOT REGEXP '^[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)*$'";

		assertThat(read("scripts/preflight-reservation-inventory-cutover.sh"))
			.contains(plausibleZoneIdPattern)
			.doesNotContain("|UTC|GMT");
		assertThat(read("infra/aws/scripts/bootstrap-data.sh"))
			.contains(plausibleZoneIdPattern);
		assertThat(read("infra/aws/scripts/capture-dataset-attestation.sh"))
			.contains(plausibleZoneIdPattern);
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
