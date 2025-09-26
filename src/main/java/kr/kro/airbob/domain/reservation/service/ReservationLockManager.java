package kr.kro.airbob.domain.reservation.service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.redisson.RedissonMultiLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.reservation.exception.ReservationLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationLockManager {

	private final RedissonClient redissonClient;
	private static final long LOCK_WAIT_TIME_SECONDS = 5;
	private static final long LOCK_LEASE_TIME_SECONDS = 10;

	public RLock acquireLocks(List<String> lockKeys) {
		List<RLock> rLocks = lockKeys.stream()
			.map(redissonClient::getLock)
			.toList();

		RedissonMultiLock multiLock = new RedissonMultiLock(rLocks.toArray(new RLock[0]));

		try {
			boolean isLockAcquired = multiLock.tryLock(LOCK_WAIT_TIME_SECONDS, LOCK_LEASE_TIME_SECONDS,
				TimeUnit.SECONDS);

			if (!isLockAcquired) {
				log.warn("다중 락 획득 실패. lockKeys={}", lockKeys);
				throw new ReservationLockException();
			}

			log.info("다중 락 획득 성공. lockKeys={}", lockKeys);
			return multiLock;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ReservationLockException("예약 처리 중 예기치 않은 오류가 발생했습니다.");
		}
	}

	public void releaseLocks(RLock lock) {
		if (lock != null && lock.isLocked()) {
			lock.unlock();
			log.info("다중 락 해제 성공.");
		}
	}

}
