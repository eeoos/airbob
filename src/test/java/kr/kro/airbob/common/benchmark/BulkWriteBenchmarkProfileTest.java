package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcOperations;

import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkAccessGuard;
import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkDatabaseGuard;

@DisplayName("대량 쓰기 벤치마크 프로필 및 property 격리 테스트")
class BulkWriteBenchmarkProfileTest {

	private static final String TOKEN = "bulk-write-benchmark-token-123456789";
	private static final String SCHEMA = "airbob_bulk_write_benchmark";

	private final JdbcOperations jdbcOperations = mock(JdbcOperations.class);
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(GuardConfiguration.class)
		.withBean(JdbcOperations.class, () -> jdbcOperations);
	private final ApplicationContextRunner configDataRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(GuardConfiguration.class)
		.withBean(JdbcOperations.class, () -> jdbcOperations);

	@BeforeEach
	void resetJdbcMock() {
		reset(jdbcOperations);
	}

	@Test
	@DisplayName("profile 없이 property만 켜면 전용 가드 bean을 만들지 않는다")
	void propertyAloneDoesNotCreateGuards() {
		contextRunner
			.withPropertyValues(validProperties())
			.run(context -> {
				assertThat(context).doesNotHaveBean(BulkWriteBenchmarkAccessGuard.class);
				assertThat(context).doesNotHaveBean(BulkWriteBenchmarkDatabaseGuard.class);
				verifyNoInteractions(jdbcOperations);
			});
	}

	@Test
	@DisplayName("profile만 켜고 enabled property가 없으면 전용 가드 bean을 만들지 않는다")
	void profileAloneDoesNotCreateGuards() {
		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.withPropertyValues(
				"benchmark.bulk-write.token=" + TOKEN,
				"benchmark.bulk-write.allowed-schema=" + SCHEMA
			)
			.run(context -> {
				assertThat(context).doesNotHaveBean(BulkWriteBenchmarkAccessGuard.class);
				assertThat(context).doesNotHaveBean(BulkWriteBenchmarkDatabaseGuard.class);
				verifyNoInteractions(jdbcOperations);
			});
	}

	@Test
	@DisplayName("전용 profile과 enabled property가 모두 있을 때만 두 가드를 초기화한다")
	void profileAndPropertyCreateGuards() {
		when(jdbcOperations.queryForObject("SELECT DATABASE()", String.class)).thenReturn(SCHEMA);
		when(jdbcOperations.queryForObject(anyString(), eq(Integer.class), eq(SCHEMA)))
			.thenReturn(6);

		contextRunner
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("bulk-write-benchmark"))
			.withPropertyValues(validProperties())
			.run(context -> {
				assertThat(context).hasSingleBean(BulkWriteBenchmarkAccessGuard.class);
				assertThat(context).hasSingleBean(BulkWriteBenchmarkDatabaseGuard.class);
			});

		verify(jdbcOperations).queryForObject("SELECT DATABASE()", String.class);
		verify(jdbcOperations).queryForObject(anyString(), eq(Integer.class), eq(SCHEMA));
	}

	@Test
	@DisplayName("실제 profile 설정을 로드해도 명시적 enabled 환경 변수가 없으면 가드를 만들지 않는다")
	void profileConfigDataRemainsDisabledWithoutExplicitEnvironmentFlag() {
		configDataRunner
			.withPropertyValues("spring.profiles.active=bulk-write-benchmark")
			.run(context -> {
				assertThat(context.getEnvironment().getProperty("benchmark.bulk-write.enabled", Boolean.class))
					.isFalse();
				assertThat(context).doesNotHaveBean(BulkWriteBenchmarkAccessGuard.class);
				assertThat(context).doesNotHaveBean(BulkWriteBenchmarkDatabaseGuard.class);
				verifyNoInteractions(jdbcOperations);
			});
	}

	@Test
	@DisplayName("전용 profile 설정은 기본값 없는 token과 허용 schema 환경 변수를 요구한다")
	void profileYamlUsesRequiredEnvironmentPlaceholders() throws IOException {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
			"application-bulk-write-benchmark.yaml",
			new ClassPathResource("application-bulk-write-benchmark.yaml")
		);

		assertThat(sources)
			.extracting(source -> source.getProperty("benchmark.bulk-write.enabled"))
			.contains("${BENCHMARK_BULK_WRITE_ENABLED:false}");
		assertThat(sources)
			.extracting(source -> source.getProperty("benchmark.bulk-write.token"))
			.containsExactly("${BENCHMARK_BULK_WRITE_TOKEN}");
		assertThat(sources)
			.extracting(source -> source.getProperty("benchmark.bulk-write.allowed-schema"))
			.containsExactly("${BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA}");
		assertThat(sources)
			.extracting(source -> source.getProperty("spring.kafka.listener.auto-startup"))
			.containsExactly(false);
		assertThat(sources)
			.extracting(source -> source.getProperty("spring.flyway.enabled"))
			.containsExactly(false);
		assertThat(sources)
			.extracting(source -> source.getProperty("spring.jpa.properties.hibernate.show_sql"))
			.containsExactly(false);
		assertThat(sources)
			.extracting(source -> source.getProperty("logging.level.org.hibernate.SQL"))
			.containsExactly("OFF");
	}

	private String[] validProperties() {
		return new String[] {
			"benchmark.bulk-write.enabled=true",
			"benchmark.bulk-write.token=" + TOKEN,
			"benchmark.bulk-write.allowed-schema=" + SCHEMA
		};
	}

	@Configuration(proxyBeanMethods = false)
	@Import({BulkWriteBenchmarkAccessGuard.class, BulkWriteBenchmarkDatabaseGuard.class})
	static class GuardConfiguration {
	}
}
