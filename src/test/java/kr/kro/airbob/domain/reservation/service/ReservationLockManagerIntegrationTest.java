package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("예약 다중 락 해제 통합 테스트")
class ReservationLockManagerIntegrationTest {

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>(
		DockerImageName.parse("redis:7.2-alpine"))
		.withExposedPorts(6379);

	private static RedissonClient redissonClient;
	private static ReservationLockManager lockManager;

	@BeforeAll
	static void setUpClient() {
		Config config = new Config();
		config.useSingleServer()
			.setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
		redissonClient = Redisson.create(config);
		lockManager = new ReservationLockManager(redissonClient);
	}

	@AfterAll
	static void shutDownClient() {
		if (redissonClient != null) {
			redissonClient.shutdown();
		}
	}

	@BeforeEach
	void clearLocks() {
		redissonClient.getKeys().deleteByPattern("LOCK:RESERVATION:release-test:*");
	}

	@Test
	@DisplayName("한 날짜 락이 먼저 해제돼도 나머지 날짜 락을 모두 해제한다")
	void releasesRemainingLocksWhenOneLockWasAlreadyReleased() {
		List<String> lockKeys = List.of(
			"LOCK:RESERVATION:release-test:1",
			"LOCK:RESERVATION:release-test:2"
		);
		RLock multiLock = lockManager.acquireLocks(lockKeys);
		RLock firstLock = redissonClient.getLock(lockKeys.getFirst());
		RLock secondLock = redissonClient.getLock(lockKeys.getLast());
		assertThat(firstLock.isLocked()).isTrue();
		assertThat(secondLock.isLocked()).isTrue();

		firstLock.unlock();
		lockManager.releaseLocks(multiLock);

		assertThat(firstLock.isLocked()).isFalse();
		assertThat(secondLock.isLocked()).isFalse();
	}
}
