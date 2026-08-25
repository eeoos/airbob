package kr.kro.airbob.domain.reservation.inventory;

import static kr.kro.airbob.config.SchedulingConfig.ACCOMMODATION_INVENTORY_SEED_TASK_SCHEDULER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccommodationInventorySeedScheduler")
class AccommodationInventorySeedSchedulerTest {

	@Mock private AccommodationInventorySeedService seedService;

	@Test
	@DisplayName("cursor를 이어가며 빈 batch 또는 partial batch에서 멈춘다")
	void advancesCursorUntilThePublishedListIsDrained() {
		given(seedService.seedNextPublishedBatch(0L, 100))
			.willReturn(new AccommodationInventorySeedService.SeedBatch(100, 400L));
		given(seedService.seedNextPublishedBatch(400L, 100))
			.willReturn(new AccommodationInventorySeedService.SeedBatch(27, 512L));
		AccommodationInventorySeedScheduler scheduler =
			new AccommodationInventorySeedScheduler(seedService, 100, 10);

		scheduler.seedPublishedInventory();

		then(seedService).should().seedNextPublishedBatch(0L, 100);
		then(seedService).should().seedNextPublishedBatch(400L, 100);
		then(seedService).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("계속 full batch여도 설정된 최대 batch 수에서 멈춘다")
	void stopsAtTheConfiguredBatchLimit() {
		given(seedService.seedNextPublishedBatch(0L, 100))
			.willReturn(new AccommodationInventorySeedService.SeedBatch(100, 100L));
		given(seedService.seedNextPublishedBatch(100L, 100))
			.willReturn(new AccommodationInventorySeedService.SeedBatch(100, 200L));
		given(seedService.seedNextPublishedBatch(200L, 100))
			.willReturn(new AccommodationInventorySeedService.SeedBatch(100, 300L));
		AccommodationInventorySeedScheduler scheduler =
			new AccommodationInventorySeedScheduler(seedService, 100, 3);

		scheduler.seedPublishedInventory();

		then(seedService).should(times(3)).seedNextPublishedBatch(
			org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(100));
	}

	@Test
	@DisplayName("max batch에서 끊기면 다음 tick이 cursor를 이어가고 빈 tail 뒤에는 처음으로 wrap한다")
	void continuesAcrossTicksAndWrapsAfterAnEmptyTail() {
		given(seedService.seedNextPublishedBatch(0L, 100))
			.willReturn(new AccommodationInventorySeedService.SeedBatch(100, 400L));
		given(seedService.seedNextPublishedBatch(400L, 100))
			.willReturn(new AccommodationInventorySeedService.SeedBatch(100, 500L));
		given(seedService.seedNextPublishedBatch(500L, 100))
			.willReturn(new AccommodationInventorySeedService.SeedBatch(0, 500L));
		AccommodationInventorySeedScheduler scheduler =
			new AccommodationInventorySeedScheduler(seedService, 100, 1);

		scheduler.seedPublishedInventory();
		scheduler.seedPublishedInventory();
		scheduler.seedPublishedInventory();
		scheduler.seedPublishedInventory();

		then(seedService).should(times(2)).seedNextPublishedBatch(0L, 100);
		then(seedService).should().seedNextPublishedBatch(400L, 100);
		then(seedService).should().seedNextPublishedBatch(500L, 100);
	}

	@Test
	@DisplayName("rolling seed는 전용 task scheduler를 사용한다")
	void usesDedicatedTaskScheduler() throws NoSuchMethodException {
		Method method = AccommodationInventorySeedScheduler.class
			.getMethod("seedPublishedInventory");
		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.scheduler())
			.isEqualTo(ACCOMMODATION_INVENTORY_SEED_TASK_SCHEDULER);
		assertThat(scheduled.fixedDelayString())
			.isEqualTo("${reservation.inventory.seed.interval:1h}");
	}

	@Test
	@DisplayName("rolling seed는 기본 활성화하고 read-only profile에서는 명시적으로 끌 수 있다")
	void supportsExplicitReadOnlyProfileDisablement() {
		ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withBean(AccommodationInventorySeedService.class,
				() -> mock(AccommodationInventorySeedService.class))
			.withUserConfiguration(AccommodationInventorySeedScheduler.class);

		contextRunner.run(context -> assertThat(context)
			.hasSingleBean(AccommodationInventorySeedScheduler.class));
		contextRunner
			.withPropertyValues("reservation.inventory.seed.enabled=false")
			.run(context -> assertThat(context)
				.doesNotHaveBean(AccommodationInventorySeedScheduler.class));
	}
}
