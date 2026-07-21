package kr.kro.airbob.common.benchmark.bulkwrite;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

@DisplayName("대량 쓰기 벤치마크 전용 토큰 가드 단위 테스트")
class BulkWriteBenchmarkAccessGuardTest {

	private static final String CONFIGURED_TOKEN = "bulk-write-benchmark-token-123456789";

	@Test
	@DisplayName("정확히 일치하는 32자 이상 전용 토큰은 허용한다")
	void matchingTokenPasses() {
		BulkWriteBenchmarkAccessGuard guard = new BulkWriteBenchmarkAccessGuard(CONFIGURED_TOKEN);

		assertThatCode(() -> guard.verify(CONFIGURED_TOKEN))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("누락되거나 다른 요청 토큰은 일반 접근 거부 오류만 반환한다")
	void invalidRequestTokenIsRejectedWithoutLeakingSecrets() {
		BulkWriteBenchmarkAccessGuard guard = new BulkWriteBenchmarkAccessGuard(CONFIGURED_TOKEN);

		assertDeniedWithoutSecret(guard, null);
		assertDeniedWithoutSecret(guard, "");
		assertDeniedWithoutSecret(guard, " ");
		assertDeniedWithoutSecret(guard, "wrong-bulk-write-benchmark-token");
		assertDeniedWithoutSecret(guard, CONFIGURED_TOKEN + " ");
	}

	@Test
	@DisplayName("비어 있거나 32자 미만인 서버 토큰은 시작 시 거부한다")
	void blankOrShortConfiguredTokenIsRejectedAtStartup() {
		assertInvalidConfiguration(null);
		assertInvalidConfiguration("");
		assertInvalidConfiguration(" ");
		assertInvalidConfiguration("a".repeat(31));
	}

	@Test
	@DisplayName("미해결 placeholder나 앞뒤 공백이 있는 서버 토큰은 보정하지 않고 거부한다")
	void unresolvedOrPaddedConfiguredTokenIsRejectedAtStartup() {
		assertInvalidConfiguration("${BENCHMARK_BULK_WRITE_TOKEN}");
		assertInvalidConfiguration(" " + CONFIGURED_TOKEN);
		assertInvalidConfiguration(CONFIGURED_TOKEN + " ");
	}

	@Test
	@DisplayName("전용 헤더 이름은 기존 조회 벤치마크 헤더와 구분한다")
	void usesDedicatedHeaderName() {
		assertThat(BulkWriteBenchmarkAccessGuard.HEADER_NAME)
			.isEqualTo("X-Bulk-Write-Benchmark-Token")
			.isNotEqualTo("X-Benchmark-Token");
	}

	private void assertInvalidConfiguration(String token) {
		assertThatThrownBy(() -> new BulkWriteBenchmarkAccessGuard(token))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Bulk-write benchmark access configuration is invalid")
			.satisfies(exception -> {
				if (token != null && !token.isBlank()) {
					assertThat(exception).hasMessageNotContaining(token);
				}
			});
	}

	private void assertDeniedWithoutSecret(BulkWriteBenchmarkAccessGuard guard, String providedToken) {
		assertThatThrownBy(() -> guard.verify(providedToken))
			.isInstanceOfSatisfying(BaseException.class, exception -> {
				assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BENCHMARK_ACCESS_DENIED);
				assertThat(exception.getMessage())
					.isEqualTo(ErrorCode.BENCHMARK_ACCESS_DENIED.getMessage())
					.doesNotContain(CONFIGURED_TOKEN);
				if (providedToken != null && !providedToken.isBlank()) {
					assertThat(exception.getMessage()).doesNotContain(providedToken);
				}
			});
	}
}
