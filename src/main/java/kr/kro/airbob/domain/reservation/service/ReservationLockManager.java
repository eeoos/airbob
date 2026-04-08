package kr.kro.airbob.domain.reservation.service;

import java.util.List;
import java.util.concurrent.TimeUnit;

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

	public RLock acquireLocks(List<String> lockKeys) {
		List<RLock> rLocks = lockKeys.stream()
			.map(redissonClient::getLock)
			.toList();

		RedissonMultiLock multiLock = new RedissonMultiLock(rLocks.toArray(new RLock[0]));

		try {
			// leaseTime 제거 -> WatchDog 활성화 (락 자동 갱신)
			boolean isLockAcquired = multiLock.tryLock(LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS);

			if (!isLockAcquired) {
				log.warn("다중 락 획득 실패. lockKeys={}", lockKeys);
				throw new ReservationLockException();
			}

			log.info("다중 락 획득 성공. lockKeys={}", lockKeys);
			return multiLock;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ReservationLockException();
		}
	}

	public void releaseLocks(RLock lock) {
		if (lock == null) {
			return;
		}
		try {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
				log.info("다중 락 해제 성공.");
			}
		} catch (Exception e) {
			log.warn("다중 락 해제 중 예외 발생. 이미 만료됐을 수 있음.", e);
		}
	}

}
