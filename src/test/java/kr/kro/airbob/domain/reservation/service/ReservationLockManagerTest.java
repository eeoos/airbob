package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import kr.kro.airbob.domain.reservation.exception.ReservationLockException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationLockManager 테스트")
class ReservationLockManagerTest {

	@InjectMocks
	private ReservationLockManager lockManager;

	@Mock
	private RedissonClient redissonClient;

	@Mock
	private RLock mockLock1;

	@Mock
	private RLock mockLock2;

	private List<String> lockKeys;

	@BeforeEach
	void setUp() {
		lockKeys = List.of(
			"LOCK:RESERVATION:1:2025-01-26",
			"LOCK:RESERVATION:1:2025-01-27"
		);
	}

	@Nested
	@DisplayName("락 획득 테스트")
	class AcquireLocksTest {

		@Test
		@DisplayName("각 락 키에 대해 getLock이 호출된다")
		void 락_키_조회_확인() {
			// given
			given(redissonClient.getLock(anyString())).willReturn(mockLock1);

			// when
			try {
				lockManager.acquireLocks(lockKeys);
			} catch (ReservationLockException e) {
				// 락 획득 실패는 예상됨 (실제 Redis 없음)
			}

			// then - 각 키에 대해 getLock 호출됨
			then(redissonClient).should(times(2)).getLock(anyString());
		}
	}

	@Nested
	@DisplayName("락 해제 테스트")
	class ReleaseLocksTest {

		@Test
		@DisplayName("다중 락은 별도 소유권 조회 없이 모든 하위 락의 비동기 해제를 기다린다")
		@SuppressWarnings("unchecked")
		void 락_해제_성공() {
			// given
			RLock mockLock = mock(RLock.class);
			RFuture<Void> unlockFuture = mock(RFuture.class);
			given(mockLock.unlockAsync()).willReturn(unlockFuture);
			given(unlockFuture.toCompletableFuture())
				.willReturn(CompletableFuture.completedFuture(null));

			// when
			lockManager.releaseLocks(mockLock);

			// then
			then(mockLock).should().unlockAsync();
			then(mockLock).should(never()).isHeldByCurrentThread();
		}

		@Test
		@DisplayName("일부 다중 락 해제 실패는 이미 끝난 예약 결과를 덮어쓰지 않는다")
		@SuppressWarnings("unchecked")
		void 락_해제_실패_격리() {
			// given
			RLock mockLock = mock(RLock.class);
			RFuture<Void> unlockFuture = mock(RFuture.class);
			CompletableFuture<Void> failedUnlock = new CompletableFuture<>();
			failedUnlock.completeExceptionally(new IllegalMonitorStateException("already released"));
			given(mockLock.unlockAsync()).willReturn(unlockFuture);
			given(unlockFuture.toCompletableFuture()).willReturn(failedUnlock);

			// when & then
			assertThatCode(() -> lockManager.releaseLocks(mockLock))
				.doesNotThrowAnyException();

			then(mockLock).should().unlockAsync();
			then(mockLock).should(never()).isHeldByCurrentThread();
		}

		@Test
		@DisplayName("null 전달 시 예외 없이 처리된다")
		void 락_해제_null_안전() {
			// when & then
			assertThatCode(() -> lockManager.releaseLocks(null))
				.doesNotThrowAnyException();
		}
	}
}
