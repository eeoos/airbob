package kr.kro.airbob.common.benchmark.bulkwrite;

import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@Profile("bulk-delete-benchmark")
public class BulkDeleteBenchmarkConfiguration {

	@Bean
	InitializingBean bulkDeleteBenchmarkSettingsGuard(Environment environment) {
		return () -> {
			Set<String> allowed = Set.of("dev", "aws", "test", "bulk-delete-benchmark", "bulk-write-benchmark");
			for (String profile : environment.getActiveProfiles()) {
				Assert.state(allowed.contains(profile), "bulk-delete-benchmark cannot mix experiment profiles");
			}
			Assert.state(environment.acceptsProfiles(Profiles.of("bulk-write-benchmark")),
				"bulk-delete-benchmark requires its bulk-write-benchmark profile group");
			Assert.state(!(environment.acceptsProfiles(Profiles.of("aws"))
				&& environment.acceptsProfiles(Profiles.of("dev"))), "Choose either dev or aws");
			Assert.state(Boolean.TRUE.equals(environment.getProperty("benchmark.bulk-write.enabled", Boolean.class)),
				"bulk-delete-benchmark requires explicit benchmark enablement");
			for (String setting : new String[] {
				"spring.datasource.hikari.read-only", "spring.flyway.enabled", "spring.jpa.show-sql",
				"spring.jpa.properties.hibernate.show_sql", "spring.jpa.properties.hibernate.format_sql",
				"spring.kafka.listener.auto-startup", "spring.kafka.admin.auto-create",
				"operator-alert.kafka.auto-startup", "accommodation.indexing.kafka.auto-startup",
				"accommodation.indexing.bootstrap.enabled", "accommodation.detail-cache.enabled",
				"accommodation.detail-cache.invalidation.kafka.auto-startup",
				"reservation.inventory.startup.enabled", "reservation.inventory.seed.enabled",
				"reservation.inventory.retention.enabled", "payment.toss.enabled", "google.api.enabled",
				"operator-alert.slack.enabled", "cloud.aws.s3.write-enabled"
			}) {
				Assert.state(Boolean.FALSE.equals(environment.getProperty(setting, Boolean.class)),
					"bulk-delete-benchmark requires " + setting + "=false");
			}
			Assert.state("none".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto")),
				"bulk-delete-benchmark must not generate schema");
			String commit = environment.getRequiredProperty("benchmark.bulk-delete.app-commit");
			Assert.state(commit.matches("[0-9a-f]{40}"), "A full benchmark app commit is required");
			if (environment.acceptsProfiles(Profiles.of("aws"))) {
				Assert.state(environment.getRequiredProperty("benchmark.bulk-delete.image-digest")
					.matches("sha256:[0-9a-f]{64}"), "AWS benchmark requires the deployed image digest");
			}
		};
	}
}
