package kr.kro.airbob.common.benchmark;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("cache-benchmark")
public class CacheBenchmarkFlywayConfiguration {

	@Bean
	FlywayMigrationStrategy cacheBenchmarkFlywayMigrationStrategy() {
		// The experiment must use an already migrated dataset; never upgrade it at startup.
		return Flyway::validate;
	}
}
