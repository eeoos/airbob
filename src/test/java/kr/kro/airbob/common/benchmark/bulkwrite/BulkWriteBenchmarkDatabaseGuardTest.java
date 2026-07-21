package kr.kro.airbob.common.benchmark.bulkwrite;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("대량 쓰기 벤치마크 disposable DB 시작 가드 단위 테스트")
class BulkWriteBenchmarkDatabaseGuardTest {

	private static final String ALLOWED_SCHEMA = "airbob_bulk_write_benchmark";

	@Test
	@DisplayName("현재 schema가 설정값과 정확히 같고 전용 suffix로 끝나면 시작을 허용한다")
	void exactDisposableSchemaPasses() throws Exception {
		JdbcOperations jdbcOperations = mock(JdbcOperations.class);
		when(jdbcOperations.queryForObject("SELECT DATABASE()", String.class)).thenReturn(ALLOWED_SCHEMA);
		when(jdbcOperations.queryForObject(anyString(), eq(Integer.class), eq(ALLOWED_SCHEMA)))
			.thenReturn(6);
		BulkWriteBenchmarkDatabaseGuard guard = guard(jdbcOperations, new MockEnvironment(), ALLOWED_SCHEMA);

		assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
		assertThatCode(guard::verifyReady).doesNotThrowAnyException();
		verify(jdbcOperations).queryForObject("SELECT DATABASE()", String.class);
		var queryCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(jdbcOperations).queryForObject(queryCaptor.capture(), eq(Integer.class), eq(ALLOWED_SCHEMA));
		assertThat(queryCaptor.getValue())
			.contains("'wishlist'", "'wishlist_accommodation'", "'reservation'", "'reservation_history'");
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
