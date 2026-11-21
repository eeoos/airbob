package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccommodationInventoryProductionProfileGuardTest {

	@Test
	void rejectsDisabledStartupOrRollingSeed() {
		assertThatThrownBy(() -> guard(false, true).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("inventory startup and rolling seed");
		assertThatThrownBy(() -> guard(true, false).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("inventory startup and rolling seed");
	}

	@Test
	void acceptsEnabledLifecycle() {
		assertThatCode(() -> guard(true, true).afterPropertiesSet())
			.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = {"aws", "oci"})
	void productionContextFailsBeforeStartupWhenLifecycleIsDisabled(String profile) {
		new ApplicationContextRunner()
			.withUserConfiguration(AccommodationInventoryProductionProfileGuard.class)
			.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
			.withPropertyValues(
				"reservation.inventory.startup.enabled=false",
				"reservation.inventory.seed.enabled=true")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseMessage(
						"AWS/OCI reservation deployments require inventory startup and rolling seed");
			});
	}

	private AccommodationInventoryProductionProfileGuard guard(boolean startupEnabled, boolean seedEnabled) {
		return new AccommodationInventoryProductionProfileGuard(startupEnabled, seedEnabled);
	}
}
