package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.springframework.test.util.ReflectionTestUtils;

class RedisConfigTest {

	@Test
	@DisplayName("반복 실행하는 Lua의 스크립트 캐시 설정은 Redis 연결 없이 검증한다")
	void createsScriptCacheEnabledConfigWithoutRedisConnection() {
		RedisConfig redisConfig = new RedisConfig();
		ReflectionTestUtils.setField(redisConfig, "redisHost", "unreachable.invalid");
		ReflectionTestUtils.setField(redisConfig, "redisPort", 1);

		Config config = redisConfig.redissonConfig();

		assertThat(config.isUseScriptCache()).isTrue();
	}
}
