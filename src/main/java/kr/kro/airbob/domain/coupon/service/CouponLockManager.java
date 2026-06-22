package kr.kro.airbob.domain.coupon.service;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.coupon.exception.CouponIssueFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 발급용 단일 분산 락. 예약의 {@code ReservationLockManager} 와 동일하게
 * Pub/Sub 기반 Redisson 락을 쓰되, 쿠폰은 단일 키({@code coupon:lock:{id}}) 만 잠근다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponLockManager {

	private final RedissonClient redissonClient;
	private static final long LOCK_WAIT_TIME_SECONDS = 5;

	public RLock acquireLock(String lockKey) {
		RLock lock = redissonClient.getLock(lockKey);
		try {
			// leaseTime 미지정 → WatchDog 자동 갱신
			boolean acquired = lock.tryLock(LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS);
			if (!acquired) {
				log.warn("쿠폰 락 획득 실패. lockKey={}", lockKey);
				throw new CouponIssueFailedException();
			}
			return lock;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CouponIssueFailedException();
		}
	}

	public void releaseLock(RLock lock) {
		if (lock == null) {
			return;
		}
		try {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		} catch (Exception e) {
			log.warn("쿠폰 락 해제 중 예외. 이미 만료됐을 수 있음.", e);
		}
	}
}
