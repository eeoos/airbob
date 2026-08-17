package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import kr.kro.airbob.domain.coupon.exception.CouponLockTimeoutException;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;

@ExtendWith(MockitoExtension.class)
class CouponLockManagerTest {

	@Mock
	private RedissonClient redissonClient;
	@Mock
	private RLock lock;
	@Mock
	private CouponIssueMetricRecorder metricRecorder;

	@InjectMocks
	private CouponLockManager lockManager;

	@Test
	@DisplayName("5초 안에 락을 얻지 못하면 매진과 다른 503 예외를 던진다")
	void throwsTimeoutExceptionWhenLockCannotBeAcquired() throws InterruptedException {
		when(redissonClient.getLock("coupon:{1}:lock")).thenReturn(lock);
		when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(false);

		assertThatThrownBy(() -> lockManager.acquireLock(1L))
			.isInstanceOf(CouponLockTimeoutException.class);
		verify(lock).tryLock(5, TimeUnit.SECONDS);
		verify(metricRecorder).recordLockWait(
			org.mockito.ArgumentMatchers.eq(CouponIssueMetricRecorder.LockResult.TIMEOUT),
			org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	@DisplayName("획득한 락은 별도 소유권 조회 없이 직접 해제한다")
	void releasesLockWithoutOwnershipProbe() {
		lockManager.releaseLock(lock);

		verify(lock).unlock();
		verify(lock, never()).isHeldByCurrentThread();
	}

	@Test
	@DisplayName("락 해제 실패는 이미 끝난 쿠폰 발급 결과를 덮어쓰지 않는다")
	void isolatesUnlockFailure() {
		doThrow(new IllegalStateException("unlock failed")).when(lock).unlock();

		assertThatCode(() -> lockManager.releaseLock(lock)).doesNotThrowAnyException();

		verify(lock).unlock();
		verify(lock, never()).isHeldByCurrentThread();
	}

	@Test
	@DisplayName("null 락은 Redis 호출 없이 무시한다")
	void ignoresNullLock() {
		assertThatCode(() -> lockManager.releaseLock(null)).doesNotThrowAnyException();

		verifyNoInteractions(lock);
	}
}
