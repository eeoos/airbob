package kr.kro.airbob.common.benchmark.bulkwrite;

import java.util.Arrays;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
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
		  AND table_name IN ('member', 'accommodation', 'wishlist', 'wishlist_accommodation')
		""";
	private static final int REQUIRED_TABLE_COUNT = 4;
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
		return Arrays.stream(environment.getActiveProfiles())
			.anyMatch(profile -> profile.equals("aws") || profile.equals("oci"));
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
