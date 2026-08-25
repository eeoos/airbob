package kr.kro.airbob.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("!bulk-write-benchmark & !traffic-benchmark & !test")
@EnableScheduling
public class SchedulingConfig {
	public static final String DEFAULT_TASK_SCHEDULER = "taskScheduler";
	public static final String RESERVATION_CLEANUP_TASK_SCHEDULER =
		"reservationCleanupTaskScheduler";
	public static final String RESERVATION_QUOTE_CLEANUP_TASK_SCHEDULER =
		"reservationQuoteCleanupTaskScheduler";

	@Bean(name = DEFAULT_TASK_SCHEDULER, destroyMethod = "shutdown")
	ThreadPoolTaskScheduler taskScheduler(
		@Value("${spring.task.scheduling.pool.size:4}") int poolSize
	) {
		if (poolSize < 1) {
			throw new IllegalArgumentException("task scheduler pool size must be positive");
		}
		return taskScheduler(poolSize, "airbob-scheduling-");
	}

	@Bean(name = RESERVATION_CLEANUP_TASK_SCHEDULER, destroyMethod = "shutdown")
	ThreadPoolTaskScheduler reservationCleanupTaskScheduler() {
		return taskScheduler(1, "reservation-cleanup-");
	}

	@Bean(name = RESERVATION_QUOTE_CLEANUP_TASK_SCHEDULER, destroyMethod = "shutdown")
	ThreadPoolTaskScheduler reservationQuoteCleanupTaskScheduler() {
		return taskScheduler(1, "reservation-quote-cleanup-");
	}

	private ThreadPoolTaskScheduler taskScheduler(int poolSize, String threadNamePrefix) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(poolSize);
		scheduler.setThreadNamePrefix(threadNamePrefix);
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(30);
		return scheduler;
	}
}
