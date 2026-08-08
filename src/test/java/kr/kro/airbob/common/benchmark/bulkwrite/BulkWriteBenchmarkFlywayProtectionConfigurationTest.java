package kr.kro.airbob.common.benchmark.bulkwrite;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("대량 쓰기 벤치마크 Flyway 보호 설정 테스트")
class BulkWriteBenchmarkFlywayProtectionConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(BulkWriteBenchmarkFlywayProtectionConfiguration.class);

	@Test
	@DisplayName("전용 profile이 아니면 Flyway 보호 전략을 만들지 않는다")
	void doesNotCreateStrategyOutsideBenchmarkProfile() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(FlywayMigrationStrategy.class));
	}

	@Test
	@DisplayName("전용 profile에서 Flyway가 활성화돼도 migrate 호출 전에 시작을 거부한다")
	void rejectsFlywayMigrationBeforeItCanMutateDatabase() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.run(context -> {
				FlywayMigrationStrategy strategy = context.getBean(FlywayMigrationStrategy.class);
				Flyway flyway = mock(Flyway.class);

				assertThatThrownBy(() -> strategy.migrate(flyway))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("pre-migrated disposable database");
				verifyNoInteractions(flyway);
			});
	}
}
