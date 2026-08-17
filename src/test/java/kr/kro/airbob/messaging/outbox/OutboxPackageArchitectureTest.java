package kr.kro.airbob.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("canonical outbox package boundary")
class OutboxPackageArchitectureTest {

	private static final Path MAIN_JAVA = Path.of("src/main/java");
	private static final Path OUTBOX_ROOT = MAIN_JAVA.resolve(
		"kr/kro/airbob/messaging/outbox");

	@Test
	void keepsNoProductionTypesInTheFlatOutboxPackage() throws IOException {
		try (var children = Files.list(OUTBOX_ROOT)) {
			assertThat(children
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java")))
				.isEmpty();
		}
	}

	@Test
	void businessCodeDependsOnlyOnTheApplicationOutboxWriterPort() throws IOException {
		List<String> outboxImports;
		try (var sources = Files.walk(MAIN_JAVA)) {
			outboxImports = sources
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> !path.startsWith(OUTBOX_ROOT))
				.flatMap(this::readLines)
				.filter(line -> line.startsWith("import kr.kro.airbob.messaging.outbox."))
				.toList();
		}

		assertThat(outboxImports)
			.isNotEmpty()
			.allMatch("import kr.kro.airbob.messaging.outbox.application.OutboxWriter;"::equals);
	}

	@Test
	void jpaPersistenceTypesDoNotLeakOutsideOutboxInfrastructure() throws IOException {
		Path jpaInfrastructure = OUTBOX_ROOT.resolve("infrastructure/jpa");
		List<String> leakingImports;
		try (var sources = Files.walk(MAIN_JAVA)) {
			leakingImports = sources
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> !path.startsWith(jpaInfrastructure))
				.flatMap(this::readLines)
				.filter(line -> line.startsWith(
					"import kr.kro.airbob.messaging.outbox.infrastructure.jpa."))
				.toList();
		}

		assertThat(leakingImports).isEmpty();
	}

	@Test
	void applicationLayerDoesNotDependOnOutboxConfigurationOrInfrastructure() throws IOException {
		Path application = OUTBOX_ROOT.resolve("application");
		List<String> outwardImports;
		try (var sources = Files.walk(application)) {
			outwardImports = sources
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.flatMap(this::readLines)
				.filter(line -> line.startsWith("import kr.kro.airbob.messaging.outbox."))
				.filter(line -> !line.startsWith(
					"import kr.kro.airbob.messaging.outbox.application."))
				.toList();
		}

		assertThat(outwardImports).isEmpty();
	}

	private java.util.stream.Stream<String> readLines(Path path) {
		try {
			return Files.readAllLines(path).stream();
		} catch (IOException exception) {
			throw new IllegalStateException("failed to read " + path, exception);
		}
	}
}
