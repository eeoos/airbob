package kr.kro.airbob.common.benchmark.bulkwrite;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("bulk-write-benchmark")
public class BulkWriteBenchmarkFlywayProtectionConfiguration {

	private static final String ERROR_MESSAGE =
		"Bulk-write benchmark requires a pre-migrated disposable database";

	@Bean
	FlywayMigrationStrategy bulkWriteBenchmarkFlywayMigrationStrategy() {
		return flyway -> {
			throw new IllegalStateException(ERROR_MESSAGE);
		};
	}
}
