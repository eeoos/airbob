package kr.kro.airbob.core.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;

class RedisConfigurationTest {

	@Test
	@DisplayName("반복 실행하는 Lua의 스크립트 캐시 설정은 Redis 연결 없이 검증한다")
	void createsScriptCacheEnabledConfigWithoutRedisConnection() {
		Config config = new RedisConfiguration().redissonConfig("unreachable.invalid", 1);

		assertThat(config.isUseScriptCache()).isTrue();
	}
}
