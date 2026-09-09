package kr.kro.airbob.common.benchmark.bulkwrite;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

@Component
@Lazy(false)
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class BulkWriteBenchmarkDatabaseGuard implements InitializingBean {

	private static final String DATABASE_NAME_QUERY = "SELECT DATABASE()";
	private static final String REQUIRED_TABLES_QUERY = """
		SELECT COUNT(*)
		FROM information_schema.tables
		WHERE table_schema = ?
		  AND table_name IN (
		    'member', 'accommodation', 'wishlist', 'wishlist_accommodation',
		    'accommodation_amenity', 'accommodation_history',
		    'reservation', 'reservation_history'
		  )
		""";
	private static final int REQUIRED_TABLE_COUNT = 8;
	private static final String REQUIRED_SCHEMA_SUFFIX = "_bulk_write_benchmark";
	private static final String VALIDATION_ERROR_MESSAGE =
		"Bulk-write benchmark database validation failed";

	private final JdbcOperations jdbcOperations;
	private final Environment environment;
	private final String allowedSchema;
	private boolean validated;

	public BulkWriteBenchmarkDatabaseGuard(
		JdbcOperations jdbcOperations,
		Environment environment,
		@Value("${benchmark.bulk-write.allowed-schema}") String allowedSchema
	) {
		this.jdbcOperations = jdbcOperations;
		this.environment = environment;
		this.allowedSchema = allowedSchema;
	}

	@Override
	public void afterPropertiesSet() {
		if (hasForbiddenCloudProfile() || !isValidAllowedSchema()) {
			throw validationFailure();
		}

		String actualSchema;
		try {
			actualSchema = jdbcOperations.queryForObject(DATABASE_NAME_QUERY, String.class);
		} catch (RuntimeException exception) {
			throw validationFailure();
		}

		if (!allowedSchema.equals(actualSchema)
			|| !actualSchema.endsWith(REQUIRED_SCHEMA_SUFFIX)) {
			throw validationFailure();
		}

		Integer requiredTables;
		try {
			requiredTables = jdbcOperations.queryForObject(
				REQUIRED_TABLES_QUERY,
				Integer.class,
				allowedSchema
			);
		} catch (RuntimeException exception) {
			throw validationFailure();
		}
		if (requiredTables == null || requiredTables != REQUIRED_TABLE_COUNT) {
			throw validationFailure();
		}
		validated = true;
	}

	public void verifyReady() {
		if (!validated) {
			throw validationFailure();
		}
	}

	private boolean hasForbiddenCloudProfile() {
		Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
		if (profiles.contains("oci")) {
			return true;
		}
		return profiles.contains("aws") && !allowsBoundedAwsQualification(profiles);
	}

	private boolean allowsBoundedAwsQualification(Set<String> profiles) {
		if (!profiles.containsAll(Set.of("growth-runtime-qualification", "performance-lab", "test"))
			|| !environment.getProperty("benchmark.bulk-write.aws-qualification-enabled", Boolean.class, false)
			|| !"airbob_growth_bulk_write_benchmark".equals(allowedSchema)) {
			return false;
		}
		for (String property : Set.of("spring.kafka.listener.auto-startup", "accommodation.indexing.kafka.auto-startup",
			"accommodation.detail-cache.invalidation.kafka.auto-startup", "operator-alert.kafka.auto-startup",
			"payment.toss.enabled", "google.api.enabled", "cloud.aws.s3.write-enabled", "operator-alert.slack.enabled")) {
			if (!Boolean.FALSE.equals(environment.getProperty(property, Boolean.class))) {
				return false;
			}
		}
		String runId = environment.getProperty("AIRBOB_RUN_ID", "");
		if (!runId.matches("lab-[a-z0-9][a-z0-9-]{0,27}") || runId.endsWith("-") || runId.contains("--")) {
			return false;
		}
		try {
			String url = jdbcOperations.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
			String prefix = "jdbc:mysql://airbob-" + runId + ".";
			if (url == null || !url.startsWith(prefix)
				|| !url.matches("jdbc:mysql://airbob-lab-[a-z0-9-]+\\.[a-z0-9]+\\.ap-northeast-2\\.rds\\.amazonaws\\.com:3306/airbob_growth_bulk_write_benchmark\\?.+")) {
				return false;
			}
			String[] properties = url.substring(url.indexOf('?') + 1).split("&");
			Set<String> permitted = Set.of("sslMode", "connectionTimeZone", "forceConnectionTimeZoneToSession",
				"trustCertificateKeyStoreType", "trustCertificateKeyStorePassword", "trustCertificateKeyStoreUrl");
			var keys = Arrays.stream(properties).map(value -> value.split("=", 2)[0]).toList();
			if (keys.size() != Set.copyOf(keys).size() || !permitted.containsAll(keys)
				|| !Arrays.asList(properties).contains("sslMode=VERIFY_IDENTITY")) {
				return false;
			}
			String account = "growth_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(runId.getBytes(StandardCharsets.UTF_8))).substring(0, 16) + "@%";
			return account.equals(jdbcOperations.queryForObject("SELECT CURRENT_USER()", String.class));
		} catch (RuntimeException | NoSuchAlgorithmException exception) {
			return false;
		}
	}

	private boolean isValidAllowedSchema() {
		return allowedSchema != null
			&& !allowedSchema.isBlank()
			&& allowedSchema.equals(allowedSchema.strip())
			&& !allowedSchema.contains("${")
			&& allowedSchema.endsWith(REQUIRED_SCHEMA_SUFFIX);
	}

	private IllegalStateException validationFailure() {
		return new IllegalStateException(VALIDATION_ERROR_MESSAGE);
	}
}
