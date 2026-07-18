package kr.kro.airbob.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

class RedisConfigTest {

	@Test
	@DisplayName("반복 실행하는 Lua는 Redisson 스크립트 캐시를 사용한다")
	void enablesScriptCache() {
		RedisConfig redisConfig = new RedisConfig();
		ReflectionTestUtils.setField(redisConfig, "redisHost", "127.0.0.1");
		ReflectionTestUtils.setField(redisConfig, "redisPort", 6379);

		RedissonClient redissonClient = redisConfig.redissonClient();
		try {
			assertThat(redissonClient.getConfig().isUseScriptCache()).isTrue();
		} finally {
			redissonClient.shutdown();
		}
	}
}
