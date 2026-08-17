package kr.kro.airbob.messaging.outbox;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "messaging.outbox.cleanup.enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxCleanupProperties.class)
public class OutboxCleanupConfiguration {

	@Bean
	public OutboxCleanupRepository outboxCleanupRepository(JdbcTemplate jdbcTemplate) {
		return new MysqlOutboxCleanupRepository(jdbcTemplate);
	}

	@Bean
	public OutboxCleanupBatchDeleter outboxCleanupBatchDeleter(
		OutboxCleanupRepository repository
	) {
		return new OutboxCleanupBatchDeleter(repository);
	}

	@Bean
	public OutboxCleanupService outboxCleanupService(
		OutboxCleanupBatchDeleter batchDeleter,
		OutboxCleanupRepository repository,
		OutboxCleanupProperties properties,
		Clock clock
	) {
		return new OutboxCleanupService(batchDeleter, repository, properties, clock);
	}

	@Bean
	public OutboxCleanupMetrics outboxCleanupMetrics(MeterRegistry meterRegistry) {
		return new OutboxCleanupMetrics(meterRegistry);
	}

	@Bean
	public OutboxCleanupScheduler outboxCleanupScheduler(
		OutboxCleanupService service,
		OutboxCleanupMetrics metrics
	) {
		return new OutboxCleanupScheduler(service, metrics);
	}
}
