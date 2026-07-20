package kr.kro.airbob.common.benchmark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

@Component
@Profile({"nplus1-benchmark", "read-model-benchmark"})
public class BenchmarkAccessGuard {

	public static final String HEADER_NAME = "X-Benchmark-Token";

	private final String configuredToken;

	public BenchmarkAccessGuard(@Value("${benchmark.read-model.token:}") String configuredToken) {
		this.configuredToken = configuredToken;
	}

	public void verify(String providedToken) {
		if (configuredToken == null || configuredToken.isBlank()
			|| providedToken == null || providedToken.isBlank()
			|| !tokensMatch(configuredToken, providedToken)) {
			throw new BaseException(ErrorCode.BENCHMARK_ACCESS_DENIED);
		}
	}

	private boolean tokensMatch(String expected, String actual) {
		return MessageDigest.isEqual(
			expected.getBytes(StandardCharsets.UTF_8),
			actual.getBytes(StandardCharsets.UTF_8)
		);
	}
}
