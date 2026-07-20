package kr.kro.airbob.common.benchmark;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

@DisplayName("벤치마크 공통 토큰 가드 단위 테스트")
class BenchmarkAccessGuardTest {

	private final BenchmarkAccessGuard guard = new BenchmarkAccessGuard("secret-token");

	@Test
	@DisplayName("설정된 토큰과 요청 토큰이 같으면 접근을 허용한다")
	void matchingTokenPasses() {
		assertThatCode(() -> guard.verify("secret-token"))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("요청 토큰이 없거나 비어 있거나 다르면 접근을 거부한다")
	void invalidTokenIsRejected() {
		assertDenied(null);
		assertDenied("");
		assertDenied(" ");
		assertDenied("wrong-token");
	}

	@Test
	@DisplayName("서버에 토큰이 설정되지 않았으면 모든 요청을 거부한다")
	void blankConfiguredTokenRejectsAllRequests() {
		BenchmarkAccessGuard blankGuard = new BenchmarkAccessGuard(" ");

		assertThatThrownBy(() -> blankGuard.verify("secret-token"))
			.isInstanceOfSatisfying(BaseException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BENCHMARK_ACCESS_DENIED));
	}

	private void assertDenied(String providedToken) {
		assertThatThrownBy(() -> guard.verify(providedToken))
			.isInstanceOfSatisfying(BaseException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BENCHMARK_ACCESS_DENIED));
	}
}
