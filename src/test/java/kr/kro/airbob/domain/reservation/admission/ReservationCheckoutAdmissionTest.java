package kr.kro.airbob.domain.reservation.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import kr.kro.airbob.domain.reservation.exception.ReservationInventoryBusyException;

class ReservationCheckoutAdmissionTest {

	@Test
	void saturatedAdmissionRejectsBeforeStartingDatabaseWorkAndCanBeReused() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		ReservationCheckoutAdmission admission = admission(dataSource, 1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<String> first = executor.submit(() -> admission.execute(() -> {
			entered.countDown();
			await(release);
			return "first";
		}));

		try {
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> admission.execute(() -> {
				throw new AssertionError("saturated checkout action must not start");
			}))
				.isInstanceOf(ReservationInventoryBusyException.class)
				.hasNoCause();
			verify(dataSource, never()).getConnection();

			release.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
			assertThat(admission.execute(() -> "reused")).isEqualTo("reused");
		} finally {
			release.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void actionFailureAlwaysReleasesItsPermit() {
		ReservationCheckoutAdmission admission = admission(mock(DataSource.class), 1);
		ExpectedCheckoutFailure failure = new ExpectedCheckoutFailure();

		assertThat(catchThrowable(() -> admission.execute(() -> {
			throw failure;
		}))).isSameAs(failure);
		assertThat(admission.execute(() -> "next-checkout")).isEqualTo("next-checkout");
	}

	@Test
	void hikariPoolLeavesAtLeastHalfAvailableForOtherWork() {
		try (HikariDataSource hikari = new HikariDataSource()) {
			hikari.setMaximumPoolSize(7);

			assertThatThrownBy(() -> new ReservationCheckoutAdmission(
				hikari, new ReservationCheckoutAdmissionProperties(4)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(
					"checkout admission must leave at least half of the Hikari pool for other work");
		}
	}

	@Test
	void nonHikariDataSourceOnlyRequiresPositiveTypedConfiguration() {
		DataSource dataSource = mock(DataSource.class);

		assertThat(new ReservationCheckoutAdmission(
			dataSource, new ReservationCheckoutAdmissionProperties(4)))
			.isNotNull();
		assertThatThrownBy(() -> new ReservationCheckoutAdmissionProperties(0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("checkout admission maxConcurrency must be positive");
	}

	private ReservationCheckoutAdmission admission(DataSource dataSource, int permits) {
		return new ReservationCheckoutAdmission(
			dataSource, new ReservationCheckoutAdmissionProperties(permits));
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting for admission test gate");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("admission test interrupted", exception);
		}
	}

	private static final class ExpectedCheckoutFailure extends RuntimeException {
	}
}
