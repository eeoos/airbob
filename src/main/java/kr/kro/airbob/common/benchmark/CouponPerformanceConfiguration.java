package kr.kro.airbob.common.benchmark;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@Profile("coupon-performance")
public class CouponPerformanceConfiguration {

	@Bean
	FlywayMigrationStrategy couponPerformanceFlywayStrategy() {
		return flyway -> {
			flyway.validate();
			var current = flyway.info().current();
			Assert.state(current != null && "28".equals(current.getVersion().getVersion()),
				"coupon-performance requires an already migrated V28 dataset");
		};
	}

	@Bean
	InitializingBean couponPerformanceSettingsGuard(Environment environment) {
		return () -> {
			Assert.state(!environment.acceptsProfiles(Profiles.of("performance-lab", "traffic-benchmark",
				"cache-benchmark", "read-model-benchmark", "bulk-write-benchmark", "nplus1-benchmark")),
				"coupon-performance must run without other experiment profiles");
			for (String setting : new String[] {
				"spring.datasource.hikari.read-only", "spring.kafka.listener.auto-startup",
				"spring.kafka.admin.auto-create", "operator-alert.kafka.auto-startup",
				"accommodation.indexing.kafka.auto-startup", "accommodation.indexing.bootstrap.enabled",
				"accommodation.detail-cache.invalidation.kafka.auto-startup",
				"reservation.inventory.startup.enabled", "reservation.inventory.seed.enabled",
				"reservation.inventory.retention.enabled", "payment.toss.enabled", "google.api.enabled",
				"operator-alert.slack.enabled", "cloud.aws.s3.write-enabled"
			}) {
				Assert.state(Boolean.FALSE.equals(environment.getProperty(setting, Boolean.class)),
					"coupon-performance requires " + setting + "=false");
			}
			Assert.state("none".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto")),
				"coupon-performance must not generate schema");
			Assert.state(Boolean.TRUE.equals(environment.getProperty("spring.flyway.enabled", Boolean.class)),
				"coupon-performance requires Flyway validation");
		};
	}
}
