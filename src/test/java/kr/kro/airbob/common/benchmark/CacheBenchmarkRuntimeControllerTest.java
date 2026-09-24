package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheProperties;

class CacheBenchmarkRuntimeControllerTest {

	private final Flyway flyway = mock(Flyway.class);
	private final BenchmarkAccessGuard guard = new BenchmarkAccessGuard("runtime-test-token");
	private final AccommodationDetailCacheProperties properties = new AccommodationDetailCacheProperties(
		true, Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofSeconds(45), Duration.ofSeconds(15),
		Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(1),
		Duration.ofSeconds(1), false);
	private final CacheBenchmarkRuntimeController controller =
		new CacheBenchmarkRuntimeController(guard, properties, flyway);

	@Test
	void reportsTheAppliedSchemaAndBoundPolicyWithoutCredentials() throws Exception {
		var info = mock(MigrationInfoService.class);
		var migration = mock(MigrationInfo.class);
		when(flyway.info()).thenReturn(info);
		when(info.current()).thenReturn(migration);
		when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("28"));
		var mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		var mvc = MockMvcBuilders.standaloneSetup(controller)
			.setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
			.addFilters(new CacheBenchmarkIsolationFilter(guard)).build();
		mvc.perform(get(CacheBenchmarkRuntimeController.PATH).header(BenchmarkAccessGuard.HEADER_NAME, "runtime-test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.flyway_version").value("28"))
			.andExpect(jsonPath("$.cache_enabled").value(true))
			.andExpect(jsonPath("$.coalescing_enabled").value(false))
			.andExpect(jsonPath("$.ttl_seconds").value(600))
			.andExpect(jsonPath("$.redis_command_timeout_ms").value(1000))
			.andExpect(jsonPath("$.password").doesNotExist())
			.andExpect(jsonPath("$.token").doesNotExist());
	}

	@Test
	void rejectsUnauthenticatedRequestsAndWritesBeforeReadingSchema() throws Exception {
		var mvc = MockMvcBuilders.standaloneSetup(controller)
			.addFilters(new CacheBenchmarkIsolationFilter(guard)).build();
		mvc.perform(get(CacheBenchmarkRuntimeController.PATH)).andExpect(status().isForbidden());
		mvc.perform(post(CacheBenchmarkRuntimeController.PATH)
			.header(BenchmarkAccessGuard.HEADER_NAME, "runtime-test-token")).andExpect(status().isNotFound());
		verifyNoInteractions(flyway);
	}

	@Test
	void controllerAlsoChecksTokenBeforeAccessingFlyway() {
		assertThatThrownBy(() -> controller.runtime("wrong-token")).isInstanceOf(BaseException.class);
		verifyNoInteractions(flyway);
	}

	@Test
	void runtimeEndpointDoesNotExistOutsideBenchmarkProfile() {
		new ApplicationContextRunner().withUserConfiguration(CacheBenchmarkRuntimeController.class)
			.run(context -> assertThat(context).doesNotHaveBean(CacheBenchmarkRuntimeController.class));
	}
}
