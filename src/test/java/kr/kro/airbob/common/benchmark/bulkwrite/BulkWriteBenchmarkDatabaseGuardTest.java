package kr.kro.airbob.common.benchmark.bulkwrite;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("대량 쓰기 벤치마크 disposable DB 시작 가드 단위 테스트")
class BulkWriteBenchmarkDatabaseGuardTest {

	private static final String ALLOWED_SCHEMA = "airbob_bulk_write_benchmark";
	private static final String GROWTH_SCHEMA = "airbob_growth_bulk_write_benchmark";
	private static final String RUN_ID = "lab-runtime-test";
	private static final String AWS_URL = "jdbc:mysql://airbob-" + RUN_ID
		+ ".abcdefghijkl.ap-northeast-2.rds.amazonaws.com:3306/" + GROWTH_SCHEMA
		+ "?sslMode=VERIFY_IDENTITY&connectionTimeZone=UTC";

	@Test
	void explicitAwsQualificationRequiresTheActualRunConnectionAndRestrictedAccount() throws Exception {
		JdbcOperations jdbc = mock(JdbcOperations.class);
		when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<String>>any())).thenReturn(AWS_URL);
		String account = "growth_" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
			.digest(RUN_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16) + "@%";
		when(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)).thenReturn(account);
		when(jdbc.queryForObject("SELECT DATABASE()", String.class)).thenReturn(GROWTH_SCHEMA);
		when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(GROWTH_SCHEMA))).thenReturn(8);
		MockEnvironment env = growthEnvironment();
		assertThatCode(guard(jdbc, env, GROWTH_SCHEMA)::afterPropertiesSet).doesNotThrowAnyException();

		when(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)).thenReturn("root@%");
		assertDatabaseRejected(guard(jdbc, env, GROWTH_SCHEMA));
	}

	@Test
	void awsQualificationRejectsOtherRunsUnverifiedTlsAndConflictingProperties() {
		for (String url : new String[] { AWS_URL.replace(RUN_ID, "lab-other-run"),
			AWS_URL.replace("VERIFY_IDENTITY", "REQUIRED"), AWS_URL + "&sslMode=DISABLED",
			AWS_URL + "&useSSL=false", AWS_URL.replace("ap-northeast-2", "us-east-1"),
			AWS_URL.replace(GROWTH_SCHEMA, "airbobdb") }) {
			JdbcOperations jdbc = mock(JdbcOperations.class);
			when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<String>>any())).thenReturn(url);
			assertDatabaseRejected(guard(jdbc, growthEnvironment(), GROWTH_SCHEMA));
			verify(jdbc, never()).queryForObject("SELECT DATABASE()", String.class);
		}
	}

	@Test
	void awsQualificationDoesNotPermitOciOrMissingIsolationProfiles() {
		for (String[] profiles : new String[][] {
			{"aws", "growth-runtime-qualification", "test"},
			{"aws", "growth-runtime-qualification", "performance-lab"},
			{"aws", "growth-runtime-qualification", "performance-lab", "test", "oci"}
		}) {
			JdbcOperations jdbc = mock(JdbcOperations.class);
			MockEnvironment env = growthEnvironment();
			env.setActiveProfiles(profiles);
			assertDatabaseRejected(guard(jdbc, env, GROWTH_SCHEMA));
			verifyNoInteractions(jdbc);
		}
	}

	private MockEnvironment growthEnvironment() {
		MockEnvironment env = new MockEnvironment().withProperty("AIRBOB_RUN_ID", RUN_ID)
			.withProperty("benchmark.bulk-write.aws-qualification-enabled", "true");
		for (String property : new String[] {"spring.kafka.listener.auto-startup", "accommodation.indexing.kafka.auto-startup",
			"accommodation.detail-cache.invalidation.kafka.auto-startup", "operator-alert.kafka.auto-startup",
			"payment.toss.enabled", "google.api.enabled", "cloud.aws.s3.write-enabled", "operator-alert.slack.enabled"}) {
			env.withProperty(property, "false");
		}
		env.setActiveProfiles("aws", "growth-runtime-qualification", "performance-lab", "test", "bulk-write-benchmark");
		return env;
	}

	@Test
	void awsQualificationRejectsEnabledExternalEffectsBeforeJdbc() {
		JdbcOperations jdbc = mock(JdbcOperations.class);
		MockEnvironment env = growthEnvironment().withProperty("payment.toss.enabled", "true");
		assertDatabaseRejected(guard(jdbc, env, GROWTH_SCHEMA));
		verifyNoInteractions(jdbc);
	}

	@Test
	@DisplayName("현재 schema가 설정값과 정확히 같고 전용 suffix로 끝나면 시작을 허용한다")
	void exactDisposableSchemaPasses() throws Exception {
		JdbcOperations jdbcOperations = mock(JdbcOperations.class);
		when(jdbcOperations.queryForObject("SELECT DATABASE()", String.class)).thenReturn(ALLOWED_SCHEMA);
		when(jdbcOperations.queryForObject(anyString(), eq(Integer.class), eq(ALLOWED_SCHEMA)))
			.thenReturn(8);
		BulkWriteBenchmarkDatabaseGuard guard = guard(jdbcOperations, new MockEnvironment(), ALLOWED_SCHEMA);

		assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
		assertThatCode(guard::verifyReady).doesNotThrowAnyException();
		verify(jdbcOperations).queryForObject("SELECT DATABASE()", String.class);
		var queryCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(jdbcOperations).queryForObject(queryCaptor.capture(), eq(Integer.class), eq(ALLOWED_SCHEMA));
		assertThat(queryCaptor.getValue())
			.contains(
				"'wishlist'",
				"'wishlist_accommodation'",
				"'accommodation_amenity'",
				"'accommodation_history'",
				"'reservation'",
				"'reservation_history'"
			);
	}

	@Test
	@DisplayName("전용 schema라도 필수 테이블이 준비되지 않았으면 시작을 거부한다")
	void unmigratedDisposableSchemaIsRejected() {
		JdbcOperations jdbcOperations = mock(JdbcOperations.class);
		when(jdbcOperations.queryForObject("SELECT DATABASE()", String.class)).thenReturn(ALLOWED_SCHEMA);
		when(jdbcOperations.queryForObject(anyString(), eq(Integer.class), eq(ALLOWED_SCHEMA)))
			.thenReturn(0);

		assertDatabaseRejected(guard(jdbcOperations, new MockEnvironment(), ALLOWED_SCHEMA));
	}

	@Test
	@DisplayName("aws 또는 oci 프로필이 활성화되면 JDBC를 호출하기 전에 시작을 거부한다")
	void cloudProfilesAreRejectedBeforeJdbcAccess() {
		assertCloudProfileRejectedBeforeJdbc("aws");
		assertCloudProfileRejectedBeforeJdbc("oci");
	}

	@Test
	@DisplayName("현재 schema가 허용 schema와 다르면 시작을 거부한다")
	void mismatchedSchemaIsRejected() {
		JdbcOperations jdbcOperations = mock(JdbcOperations.class);
		when(jdbcOperations.queryForObject("SELECT DATABASE()", String.class))
			.thenReturn("another_bulk_write_benchmark");

		assertDatabaseRejected(guard(jdbcOperations, new MockEnvironment(), ALLOWED_SCHEMA));
	}

	@Test
	@DisplayName("설정값과 같아도 전용 suffix가 아닌 schema는 시작을 거부한다")
	void schemaWithoutDisposableSuffixIsRejected() {
		JdbcOperations jdbcOperations = mock(JdbcOperations.class);
		when(jdbcOperations.queryForObject("SELECT DATABASE()", String.class)).thenReturn("airbob_dev");

		assertDatabaseRejected(guard(jdbcOperations, new MockEnvironment(), "airbob_dev"));
	}

	@Test
	@DisplayName("비어 있거나 미해결 placeholder인 허용 schema 설정은 JDBC 전에 거부한다")
	void invalidAllowedSchemaIsRejectedBeforeJdbcAccess() {
		assertInvalidAllowedSchema(null);
		assertInvalidAllowedSchema("");
		assertInvalidAllowedSchema(" ");
		assertInvalidAllowedSchema("${BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA}");
		assertInvalidAllowedSchema(" " + ALLOWED_SCHEMA);
		assertInvalidAllowedSchema(ALLOWED_SCHEMA + " ");
	}

	@Test
	@DisplayName("JDBC 검증 실패는 연결 정보나 원인 예외를 노출하지 않는다")
	void jdbcFailureDoesNotLeakCredentials() {
		String credential = "sentinel-user:sentinel-password";
		JdbcOperations jdbcOperations = mock(JdbcOperations.class);
		when(jdbcOperations.queryForObject("SELECT DATABASE()", String.class))
			.thenThrow(new DataAccessResourceFailureException("jdbc:mysql://" + credential + "@database"));

		assertThatThrownBy(guard(jdbcOperations, new MockEnvironment(), ALLOWED_SCHEMA)::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Bulk-write benchmark database validation failed")
			.hasMessageNotContaining(credential)
			.hasNoCause();
	}

	private void assertCloudProfileRejectedBeforeJdbc(String cloudProfile) {
		JdbcOperations jdbcOperations = mock(JdbcOperations.class);
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("bulk-write-benchmark", cloudProfile);

		assertDatabaseRejected(guard(jdbcOperations, environment, ALLOWED_SCHEMA));
		verifyNoInteractions(jdbcOperations);
	}

	private void assertInvalidAllowedSchema(String allowedSchema) {
		JdbcOperations jdbcOperations = mock(JdbcOperations.class);

		assertDatabaseRejected(guard(jdbcOperations, new MockEnvironment(), allowedSchema));
		verifyNoInteractions(jdbcOperations);
	}

	private BulkWriteBenchmarkDatabaseGuard guard(
		JdbcOperations jdbcOperations,
		MockEnvironment environment,
		String allowedSchema
	) {
		return new BulkWriteBenchmarkDatabaseGuard(jdbcOperations, environment, allowedSchema);
	}

	private void assertDatabaseRejected(BulkWriteBenchmarkDatabaseGuard guard) {
		assertThatThrownBy(guard::afterPropertiesSet)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Bulk-write benchmark database validation failed");
		assertThatThrownBy(guard::verifyReady)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Bulk-write benchmark database validation failed");
	}
}
