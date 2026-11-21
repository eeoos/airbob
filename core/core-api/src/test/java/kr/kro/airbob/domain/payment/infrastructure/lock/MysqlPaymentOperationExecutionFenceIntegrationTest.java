package kr.kro.airbob.domain.payment.infrastructure.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import kr.kro.airbob.domain.payment.exception.PaymentOperationExecutionFenceUnavailableException;

@Testcontainers
class MysqlPaymentOperationExecutionFenceIntegrationTest {

	private static final UUID OPERATION_UID =
		UUID.fromString("93ccb18f-b0ea-4078-9a56-8df428328461");

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbobdb_payment_execution_fence");

	private MysqlPaymentOperationExecutionFence fence;

	@BeforeEach
	void setUp() {
		fence = new MysqlPaymentOperationExecutionFence(
			dataSource(), Duration.ofSeconds(1), Duration.ofSeconds(2), 2);
	}

	@Test
	void localPermitTimesOutBeforeBorrowingAnotherSharedPoolConnection() throws Exception {
		CountingDataSource countingDataSource = new CountingDataSource(dataSource());
		MysqlPaymentOperationExecutionFence singlePermitFence =
			new MysqlPaymentOperationExecutionFence(
				countingDataSource, Duration.ofMillis(200), Duration.ofSeconds(2), 1);
		CountDownLatch firstAcquired = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicBoolean competingActionRan = new AtomicBoolean();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<?> holder = executor.submit(() -> singlePermitFence.execute(OPERATION_UID, () -> {
			firstAcquired.countDown();
			await(releaseFirst);
			return null;
		}));

		try {
			assertThat(firstAcquired.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> singlePermitFence.execute(UUID.randomUUID(), () -> {
				competingActionRan.set(true);
				return null;
			})).isInstanceOf(PaymentOperationExecutionFenceUnavailableException.class);
			assertThat(competingActionRan).isFalse();
			assertThat(countingDataSource.borrowCount()).isOne();
		} finally {
			releaseFirst.countDown();
			holder.get(5, TimeUnit.SECONDS);
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(singlePermitFence.execute(OPERATION_UID, () -> "available"))
			.isEqualTo("available");
		assertThat(countingDataSource.borrowCount()).isEqualTo(2);
	}

	@Test
	void concurrentAcquisitionTimesOutWithoutRunningTheUnfencedAction() throws Exception {
		CountDownLatch firstAcquired = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicBoolean competingActionRan = new AtomicBoolean();
		ExecutorService executor = Executors.newSingleThreadExecutor();

		Future<?> holder = executor.submit(() -> fence.execute(OPERATION_UID, () -> {
			firstAcquired.countDown();
			await(releaseFirst);
			return null;
		}));

		try {
			assertThat(firstAcquired.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> fence.execute(OPERATION_UID, () -> {
				competingActionRan.set(true);
				return null;
			})).isInstanceOf(PaymentOperationExecutionFenceUnavailableException.class);
			assertThat(competingActionRan).isFalse();
		} finally {
			releaseFirst.countDown();
			holder.get(5, TimeUnit.SECONDS);
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(fence.execute(OPERATION_UID, () -> "available")).isEqualTo("available");
	}

	@Test
	void actionFailureStillReleasesTheConnectionScopedLock() {
		MysqlPaymentOperationExecutionFence singlePermitFence =
			new MysqlPaymentOperationExecutionFence(
				dataSource(), Duration.ofSeconds(1), Duration.ofSeconds(2), 1);

		assertThatThrownBy(() -> singlePermitFence.execute(OPERATION_UID, () -> {
			throw new ExpectedActionFailure();
		})).isInstanceOf(ExpectedActionFailure.class);

		assertThat(singlePermitFence.execute(OPERATION_UID, () -> "released"))
			.isEqualTo("released");
	}

	@Test
	void refusesToBorrowTheExecutionFenceConnectionInsideASpringTransaction() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		try {
			assertThatThrownBy(() -> fence.execute(OPERATION_UID, () -> null))
				.isInstanceOf(PaymentOperationExecutionFenceUnavailableException.class);
		} finally {
			TransactionSynchronizationManager.clear();
		}
	}

	@Test
	void lockNameContainsOnlyTheNonSensitiveOperationUidAndStaysWithinMysqlLimit() {
		String lockName = MysqlPaymentOperationExecutionFence.lockName(OPERATION_UID);

		assertThat(lockName)
			.isEqualTo("airbob:payment-operation:" + OPERATION_UID)
			.hasSizeLessThanOrEqualTo(64);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting for test release");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("test worker interrupted", interrupted);
		}
	}

	private static DataSource dataSource() {
		return new DriverManagerDataSource(
			MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
	}

	private static final class CountingDataSource extends DelegatingDataSource {
		private final AtomicInteger borrowCount = new AtomicInteger();

		private CountingDataSource(DataSource targetDataSource) {
			super(targetDataSource);
		}

		@Override
		public Connection getConnection() throws SQLException {
			borrowCount.incrementAndGet();
			return super.getConnection();
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			borrowCount.incrementAndGet();
			return super.getConnection(username, password);
		}

		private int borrowCount() {
			return borrowCount.get();
		}
	}

	private static final class ExpectedActionFailure extends RuntimeException {
	}
}
