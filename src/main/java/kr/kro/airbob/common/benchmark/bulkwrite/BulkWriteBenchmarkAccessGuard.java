package kr.kro.airbob.common.benchmark.bulkwrite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

@Component
@Lazy(false)
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
public class BulkWriteBenchmarkAccessGuard {

	public static final String HEADER_NAME = "X-Bulk-Write-Benchmark-Token";

	private static final int MINIMUM_TOKEN_LENGTH = 32;
	private static final String CONFIGURATION_ERROR_MESSAGE =
		"Bulk-write benchmark access configuration is invalid";

	private final byte[] configuredToken;

	public BulkWriteBenchmarkAccessGuard(@Value("${benchmark.bulk-write.token}") String configuredToken) {
		validateConfiguredToken(configuredToken);
		this.configuredToken = configuredToken.getBytes(StandardCharsets.UTF_8);
	}

	public void verify(String providedToken) {
		if (providedToken == null || !tokensMatch(providedToken)) {
			throw new BaseException(ErrorCode.BENCHMARK_ACCESS_DENIED);
		}
	}

	private void validateConfiguredToken(String token) {
		if (token == null
			|| token.isBlank()
			|| token.length() < MINIMUM_TOKEN_LENGTH
			|| !token.equals(token.strip())
			|| token.contains("${")) {
			throw new IllegalStateException(CONFIGURATION_ERROR_MESSAGE);
		}
	}

	private boolean tokensMatch(String providedToken) {
		return MessageDigest.isEqual(
			configuredToken,
			providedToken.getBytes(StandardCharsets.UTF_8)
		);
	}
}
