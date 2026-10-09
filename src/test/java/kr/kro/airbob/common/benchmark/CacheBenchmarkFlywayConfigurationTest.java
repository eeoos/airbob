package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CacheBenchmarkFlywayConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(CacheBenchmarkFlywayConfiguration.class);

	@Test
	void regularStartupKeepsTheDefaultMigrationStrategy() {
		runner.run(context -> assertThat(context).doesNotHaveBean(FlywayMigrationStrategy.class));
	}

	@Test
	void benchmarkStartupValidatesWithoutMigratingOrRepairing() {
		runner.withPropertyValues("spring.profiles.active=cache-benchmark").run(context -> {
			Flyway flyway = mock(Flyway.class);
			context.getBean(FlywayMigrationStrategy.class).migrate(flyway);
			verify(flyway).validate();
			verifyNoMoreInteractions(flyway);
		});
	}

	@Test
	void validationFailureStopsStartupWithoutTryingToMigrate() {
		runner.withPropertyValues("spring.profiles.active=cache-benchmark").run(context -> {
			Flyway flyway = mock(Flyway.class);
			doThrow(new FlywayException("pending migration")).when(flyway).validate();
			assertThatThrownBy(() -> context.getBean(FlywayMigrationStrategy.class).migrate(flyway))
				.isInstanceOf(FlywayException.class);
			verify(flyway).validate();
			verifyNoMoreInteractions(flyway);
		});
	}
}
