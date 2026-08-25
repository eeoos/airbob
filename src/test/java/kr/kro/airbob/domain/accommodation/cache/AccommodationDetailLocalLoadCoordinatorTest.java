package kr.kro.airbob.domain.accommodation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;

@DisplayName("숙소 상세 로컬 단일 조회 조정자 단위 테스트")
class AccommodationDetailLocalLoadCoordinatorTest {

	@Test
	@DisplayName("분리된 이전 조회가 끝나도 이후에 등록된 조회는 제거하지 않는다")
	void detachedOldLoadCompletionDoesNotRemoveReplacementLoad() throws Exception {
		AccommodationDetailLocalLoadCoordinator coordinator =
			new AccommodationDetailLocalLoadCoordinator(
				mock(AccommodationDetailCacheMetricRecorder.class), Duration.ofSeconds(5));
		CountDownLatch oldLoadStarted = new CountDownLatch(1);
		CountDownLatch releaseOldLoad = new CountDownLatch(1);
		CompletableFuture<AccommodationDetailSnapshot> oldLoad = CompletableFuture.supplyAsync(() ->
			coordinator.loadOrJoin(1L, () -> {
				oldLoadStarted.countDown();
				await(releaseOldLoad);
				return snapshot("old");
			}));
		assertThat(oldLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();

		coordinator.detachCurrent(1L);
		CountDownLatch replacementStarted = new CountDownLatch(1);
		CountDownLatch releaseReplacement = new CountDownLatch(1);
		CompletableFuture<AccommodationDetailSnapshot> replacement = CompletableFuture.supplyAsync(() ->
			coordinator.loadOrJoin(1L, () -> {
				replacementStarted.countDown();
				await(releaseReplacement);
				return snapshot("replacement");
			}));
		assertThat(replacementStarted.await(5, TimeUnit.SECONDS)).isTrue();

		releaseOldLoad.countDown();
		assertThat(oldLoad.get(5, TimeUnit.SECONDS).name()).isEqualTo("old");
		AtomicInteger unexpectedLoads = new AtomicInteger();
		AtomicReference<Thread> followerThread = new AtomicReference<>();
		CompletableFuture<AccommodationDetailSnapshot> follower = CompletableFuture.supplyAsync(() -> {
			followerThread.set(Thread.currentThread());
			return coordinator.loadOrJoin(1L, () -> {
				unexpectedLoads.incrementAndGet();
				return snapshot("unexpected");
			});
		});
		awaitWaiting(followerThread);

		releaseReplacement.countDown();

		assertThat(replacement.get(5, TimeUnit.SECONDS).name()).isEqualTo("replacement");
		assertThat(follower.get(5, TimeUnit.SECONDS).name()).isEqualTo("replacement");
		assertThat(unexpectedLoads).hasValue(0);
	}

	private static AccommodationDetailSnapshot snapshot(String name) {
		return new AccommodationDetailSnapshot(
			1L, name, null, null, null, null, null, null, "Asia/Seoul",
			null, null, null, null, List.of(), List.of(), null);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("latch wait timed out");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private static void awaitWaiting(AtomicReference<Thread> threadReference) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Thread thread = threadReference.get();
			if (thread != null
				&& (thread.getState() == Thread.State.WAITING
					|| thread.getState() == Thread.State.TIMED_WAITING)) {
				return;
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
		}
		throw new IllegalStateException("replacement 조회 follower가 대기 상태에 진입하지 않음");
	}
}
