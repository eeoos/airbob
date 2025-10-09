package kr.kro.airbob.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@EnableRetry
public class AsyncConfig {

	private static final int PROCESSORS = Math.max(2, Runtime.getRuntime().availableProcessors());

	@Bean(name = "elasticsearchTaskExecutor")
	public TaskExecutor elasticsearchTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(PROCESSORS * 2);
		executor.setMaxPoolSize(PROCESSORS * 2);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("es-indexing-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);
		executor.initialize();
		return executor;
	}

	@Bean(name = "generalTaskExecutor")
	public TaskExecutor generalTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(PROCESSORS);
		executor.setMaxPoolSize(PROCESSORS);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("general-async-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);
		executor.initialize();
		return executor;
	}
}
