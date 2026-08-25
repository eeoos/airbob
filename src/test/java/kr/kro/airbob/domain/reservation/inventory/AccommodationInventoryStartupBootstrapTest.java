package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
@DisplayName("Accommodation inventory startup bootstrap")
class AccommodationInventoryStartupBootstrapTest {

	@Mock private AccommodationInventorySeedService seedService;

	@Test
	@DisplayName("readiness stays DOWN until every published accommodation batch is verified")
	void opensReadinessOnlyAfterTheFullPublishedScan() {
		AccommodationInventoryReadiness readiness = readiness(true);
		given(seedService.seedNextPublishedBatch(0L, 2))
			.willReturn(new AccommodationInventorySeedService.SeedBatch(2, 19L));
		given(seedService.seedNextPublishedBatch(19L, 2))
			.willReturn(new AccommodationInventorySeedService.SeedBatch(1, 23L));
		AccommodationInventoryStartupBootstrap bootstrap =
			new AccommodationInventoryStartupBootstrap(seedService, readiness, 2);

		assertThat(readiness.isReady()).isFalse();
		assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);

		bootstrap.run(null);

		assertThat(readiness.isReady()).isTrue();
		assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
	}

	@Test
	@DisplayName("invalid timezone or incomplete coverage fails startup and never opens readiness")
	void failsStartupClosed() {
		AccommodationInventoryReadiness readiness = readiness(true);
		given(seedService.seedNextPublishedBatch(0L, 200))
			.willThrow(new IllegalArgumentException("invalid inventory timezone"));
		AccommodationInventoryStartupBootstrap bootstrap =
			new AccommodationInventoryStartupBootstrap(seedService, readiness, 200);

		assertThatThrownBy(() -> bootstrap.run(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("timezone");
		assertThat(readiness.isReady()).isFalse();
		assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
	}

	@Test
	@DisplayName("test와 performance-lab만 disabled bootstrap을 명시적으로 우회한다")
	void disabledBootstrapIsReadyOnlyForExplicitBypassProfiles() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("performance-lab");
		AccommodationInventoryReadiness readiness =
			new AccommodationInventoryReadiness(false, environment);

		assertThat(readiness.isReady()).isTrue();
		assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
		assertThat(readiness.health().getDetails()).containsEntry("bootstrap", "profile-bypass");
	}

	@Test
	@DisplayName("property false만으로는 readiness를 열 수 없다")
	void disabledBootstrapWithoutBypassProfileStaysDown() {
		AccommodationInventoryReadiness readiness =
			new AccommodationInventoryReadiness(false, new MockEnvironment());

		assertThat(readiness.isReady()).isFalse();
		assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
		assertThat(readiness.health().getDetails())
			.containsEntry("bootstrap", "disabled-without-bypass");
	}

	@Test
	@DisplayName("test isolation property omits the DB bootstrap runner and reports bypass readiness")
	void disabledPropertyNeedsNoDatabaseOrInventoryTable() {
		new ApplicationContextRunner()
			.withBean(AccommodationInventorySeedService.class,
				() -> mock(AccommodationInventorySeedService.class))
			.withUserConfiguration(
				AccommodationInventoryReadiness.class,
				AccommodationInventoryStartupBootstrap.class)
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
			.withPropertyValues("reservation.inventory.startup.enabled=false")
			.run(context -> {
				assertThat(context)
					.doesNotHaveBean(AccommodationInventoryStartupBootstrap.class);
				assertThat(context.getBean(AccommodationInventoryReadiness.class)
					.health().getStatus()).isEqualTo(Status.UP);
			});
	}

	private AccommodationInventoryReadiness readiness(boolean bootstrapRequired) {
		return new AccommodationInventoryReadiness(bootstrapRequired, new MockEnvironment());
	}
}
