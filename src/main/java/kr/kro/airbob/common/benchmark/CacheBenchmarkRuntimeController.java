package kr.kro.airbob.common.benchmark;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.domain.accommodation.cache.config.AccommodationDetailCacheProperties;
import lombok.RequiredArgsConstructor;

/** 측정 전 실제 바인딩된 정책과 DB 버전을 확인한다. 자격증명이나 접속 주소는 반환하지 않는다. */
@RestController
@RequiredArgsConstructor
@Profile("cache-benchmark")
public class CacheBenchmarkRuntimeController {

	public static final String PATH = "/api/v2/benchmark/cache-runtime";

	private final BenchmarkAccessGuard accessGuard;
	private final AccommodationDetailCacheProperties properties;
	private final Flyway flyway;

	@GetMapping(PATH)
	public RuntimeInfo runtime(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String token
	) {
		accessGuard.verify(token);
		var current = flyway.info().current();
		return new RuntimeInfo(current == null ? null : current.getVersion().toString(), properties.enabled(),
			properties.localLoadCoalescingEnabled(), properties.ttl().toSeconds(), properties.ttlJitter().toSeconds(),
			properties.redisCommandTimeout().toMillis(), properties.localLoadWait().toMillis());
	}

	public record RuntimeInfo(String flywayVersion, boolean cacheEnabled, boolean coalescingEnabled,
		long ttlSeconds, long ttlJitterSeconds, long redisCommandTimeoutMs, long localLoadWaitMs) {
	}
}
