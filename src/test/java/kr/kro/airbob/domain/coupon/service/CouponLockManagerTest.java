package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
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

@ExtendWith(MockitoExtension.class)
class CouponLockManagerTest {

	@Mock
	private RedissonClient redissonClient;
	@Mock
	private RLock lock;

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
	}
}
