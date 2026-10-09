package kr.kro.airbob.common.benchmark.bulkwrite;

import java.sql.SQLException;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.jdbc.JdbcConnection;
import com.zaxxer.hikari.HikariDataSource;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;

@RestController
@Profile("bulk-delete-benchmark")
public class BulkDeleteBenchmarkRuntimeController {

	private final BulkDeleteBenchmarkRuntimeIdentity identity;
	private final BulkWriteBenchmarkAccessGuard accessGuard;
	private final BulkWriteBenchmarkDatabaseGuard databaseGuard;
	private final JdbcOperations jdbc;
	private final HikariDataSource dataSource;
	private final Environment environment;

	public BulkDeleteBenchmarkRuntimeController(BulkWriteBenchmarkAccessGuard accessGuard,
		BulkWriteBenchmarkDatabaseGuard databaseGuard, JdbcOperations jdbc, HikariDataSource dataSource,
		Environment environment, BulkDeleteBenchmarkRuntimeIdentity identity) {
		this.accessGuard = accessGuard;
		this.databaseGuard = databaseGuard;
		this.jdbc = jdbc;
		this.dataSource = dataSource;
		this.environment = environment;
		this.identity = identity;
	}

	@GetMapping("/api/v2/admin/benchmarks/bulk-write/runtime")
	public ApiResponse<RuntimeInfo> runtime(
		@RequestHeader(value = BulkWriteBenchmarkAccessGuard.HEADER_NAME, required = false) String token,
		@CurrentMemberId Long memberId
	) throws SQLException {
		accessGuard.verify(token);
		databaseGuard.verifyReady();
		if (!Integer.valueOf(1).equals(jdbc.queryForObject(
			"SELECT COUNT(*) FROM member WHERE id = ? AND role = 'ADMIN' AND status = 'ACTIVE'",
			Integer.class, memberId))) {
			throw new BaseException(ErrorCode.BENCHMARK_ACCESS_DENIED);
		}
		String version = jdbc.queryForObject(BulkWriteBenchmarkDatabaseGuard.SCHEMA_VERSION_QUERY, String.class);
		String databaseId = jdbc.queryForObject("SELECT @@server_uuid", String.class);
		try (var connection = dataSource.getConnection()) {
			boolean rewrite = connection.unwrap(JdbcConnection.class).getPropertySet()
				.getBooleanProperty(PropertyKey.rewriteBatchedStatements).getValue();
			return ApiResponse.success(new RuntimeInfo("bulk-delete-runtime-v1", identity.value(),
				environment.acceptsProfiles(Profiles.of("aws")) ? "aws" : "local",
				environment.getRequiredProperty("benchmark.bulk-delete.app-commit"),
				environment.getRequiredProperty("benchmark.bulk-delete.image-digest"),
				connection.getCatalog(), version, databaseId,
				System.getProperty("java.version"), connection.getMetaData().getDatabaseProductVersion(),
				rewrite, dataSource.getMaximumPoolSize()));
		}
	}

	public record RuntimeInfo(String schemaVersion, String runtimeId, String environment,
		String appCommit, String imageDigest, String schemaLabel, String flywayVersion, String databaseId,
		String jvmVersion, String mysqlVersion, boolean rewriteBatchedStatements, int poolSize) {
	}
}
