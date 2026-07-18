package kr.kro.airbob.domain.coupon.service;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.coupon.exception.CouponLockTimeoutException;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰 발급용 단일 분산 락. 예약의 {@code ReservationLockManager} 와 동일하게
 * Pub/Sub 기반 Redisson 락을 쓰되, 쿠폰은 단일 키({@code coupon:{id}:lock}) 만 잠근다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponLockManager {

	private final RedissonClient redissonClient;
	private final CouponIssueMetricRecorder metricRecorder;
	private static final long LOCK_WAIT_TIME_SECONDS = 5;

	public RLock acquireLock(Long couponId) {
		long waitStartedAt = System.nanoTime();
		CouponIssueMetricRecorder.LockResult result = CouponIssueMetricRecorder.LockResult.ERROR;
		String lockKey = lockKey(couponId);
		try {
			RLock lock = redissonClient.getLock(lockKey);
			// leaseTime 미지정 → WatchDog 자동 갱신
			boolean acquired = lock.tryLock(LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS);
			if (!acquired) {
				result = CouponIssueMetricRecorder.LockResult.TIMEOUT;
				log.warn("쿠폰 락 획득 실패. lockKey={}", lockKey);
				throw new CouponLockTimeoutException();
			}
			result = CouponIssueMetricRecorder.LockResult.ACQUIRED;
			return lock;
		} catch (InterruptedException e) {
			result = CouponIssueMetricRecorder.LockResult.INTERRUPTED;
			Thread.currentThread().interrupt();
			throw new CouponLockTimeoutException();
		} finally {
			metricRecorder.recordLockWait(result, System.nanoTime() - waitStartedAt);
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

	private String lockKey(Long couponId) {
		return "coupon:{" + couponId + "}:lock";
	}
}
