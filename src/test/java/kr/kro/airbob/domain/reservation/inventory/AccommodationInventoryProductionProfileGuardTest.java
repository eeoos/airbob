package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class AccommodationInventoryProductionProfileGuardTest {

	@Test
	void awsAndOciRequireStartupAndRollingSeed() {
		assertThatThrownBy(() -> guard("aws", false, true).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("inventory startup and rolling seed");
		assertThatThrownBy(() -> guard("oci", true, false).afterPropertiesSet())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("inventory startup and rolling seed");
	}

	@Test
	void reservationCapableProductionProfilesAcceptTheRequiredLifecycle() {
		assertThatCode(() -> guard("aws", true, true).afterPropertiesSet())
			.doesNotThrowAnyException();
		assertThatCode(() -> guard("oci", true, true).afterPropertiesSet())
			.doesNotThrowAnyException();
	}

	@Test
	void onlyPerformanceLabRetainsTheExplicitReadOnlyBypass() {
		assertThatCode(() -> guard(new String[] {"aws", "performance-lab"}, false, false)
			.afterPropertiesSet()).doesNotThrowAnyException();
		assertThatThrownBy(() -> guard(new String[] {"oci", "test"}, false, false)
			.afterPropertiesSet()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void productionContextFailsBeforeStartupWhenLifecycleIsDisabled() {
		new ApplicationContextRunner()
			.withUserConfiguration(AccommodationInventoryProductionProfileGuard.class)
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("oci"))
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

	@Test
	void performanceLabContextMayDisableTheLifecycleExplicitly() {
		new ApplicationContextRunner()
			.withUserConfiguration(AccommodationInventoryProductionProfileGuard.class)
			.withInitializer(context ->
				context.getEnvironment().setActiveProfiles("aws", "performance-lab"))
			.withPropertyValues(
				"reservation.inventory.startup.enabled=false",
				"reservation.inventory.seed.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context)
					.hasSingleBean(AccommodationInventoryProductionProfileGuard.class);
			});
	}

	private AccommodationInventoryProductionProfileGuard guard(
		String profile,
		boolean startupEnabled,
		boolean seedEnabled
	) {
		return guard(new String[] {profile}, startupEnabled, seedEnabled);
	}

	private AccommodationInventoryProductionProfileGuard guard(
		String[] profiles,
		boolean startupEnabled,
		boolean seedEnabled
	) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profiles);
		return new AccommodationInventoryProductionProfileGuard(
			environment, startupEnabled, seedEnabled);
	}
}
