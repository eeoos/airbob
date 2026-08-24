package kr.kro.airbob.domain.payment.infrastructure.lock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.zaxxer.hikari.HikariDataSource;

import kr.kro.airbob.domain.payment.exception.PaymentOperationExecutionFenceUnavailableException;
import kr.kro.airbob.domain.payment.service.PaymentOperationExecutionFence;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class MysqlPaymentOperationExecutionFence implements PaymentOperationExecutionFence {
	private static final String LOCK_NAME_PREFIX = "airbob:payment-operation:";
	private static final int MYSQL_LOCK_NAME_MAX_LENGTH = 64;
	private static final int DEFAULT_HIKARI_MAXIMUM_POOL_SIZE = 10;
	private static final String ACQUIRE_SQL = "SELECT GET_LOCK(?, ?)";
	private static final String RELEASE_SQL = "SELECT RELEASE_LOCK(?)";

	private final DataSource dataSource;
	private final HikariDataSource hikariDataSource;
	private final int acquisitionTimeoutSeconds;
	private final long acquisitionTimeoutNanos;
	private final int networkTimeoutSeconds;
	private final int networkTimeoutMillis;
	private final Semaphore localPermits;

	public MysqlPaymentOperationExecutionFence(
		DataSource dataSource,
		@Value("${payment.operation.execution-fence-timeout:15s}") Duration acquisitionTimeout,
		@Value("${payment.operation.execution-fence-network-timeout:20s}") Duration networkTimeout,
		@Value("${payment.operation.execution-fence-max-concurrency:2}") int maxConcurrency
	) {
		this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
		this.acquisitionTimeoutSeconds = timeoutSeconds(acquisitionTimeout, "acquisitionTimeout");
		this.acquisitionTimeoutNanos = acquisitionTimeout.toNanos();
		this.networkTimeoutSeconds = timeoutSeconds(networkTimeout, "networkTimeout");
		this.networkTimeoutMillis = timeoutMillis(networkTimeout);
		validateNetworkTimeout(
			acquisitionTimeoutSeconds, networkTimeoutSeconds, networkTimeoutMillis);
		this.hikariDataSource = findHikariDataSource(dataSource);
		validateMaxConcurrency(maxConcurrency, hikariDataSource);
		this.localPermits = new Semaphore(maxConcurrency, true);
	}

	@Override
	public <T> T execute(UUID operationUid, Supplier<T> action) {
		Objects.requireNonNull(operationUid, "operationUid must not be null");
		Objects.requireNonNull(action, "action must not be null");
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new PaymentOperationExecutionFenceUnavailableException();
		}

		String lockName = lockName(operationUid);
		acquireLocalPermit();
		Connection connection = null;
		boolean lockAcquired = false;
		boolean actionStarted = false;
		Throwable actionFailure = null;
		try {
			connection = dataSource.getConnection();
			connection.setNetworkTimeout(Runnable::run, networkTimeoutMillis);
			acquireMysqlLock(connection, lockName);
			lockAcquired = true;
			actionStarted = true;
			try {
				return action.get();
			} catch (RuntimeException | Error failure) {
				actionFailure = failure;
				throw failure;
			}
		} catch (SQLException failure) {
			throw new PaymentOperationExecutionFenceUnavailableException();
		} finally {
			try {
				cleanupConnection(
					operationUid, connection, lockName, lockAcquired, actionStarted, actionFailure);
			} finally {
				localPermits.release();
			}
		}
	}

	static String lockName(UUID operationUid) {
		String lockName = LOCK_NAME_PREFIX + Objects.requireNonNull(operationUid);
		if (lockName.length() > MYSQL_LOCK_NAME_MAX_LENGTH) {
			throw new IllegalArgumentException("payment operation lock name exceeds MySQL limit");
		}
		return lockName;
	}

	private void acquireLocalPermit() {
		try {
			if (!localPermits.tryAcquire(acquisitionTimeoutNanos, TimeUnit.NANOSECONDS)) {
				throw new PaymentOperationExecutionFenceUnavailableException();
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new PaymentOperationExecutionFenceUnavailableException();
		}
	}

	private void acquireMysqlLock(Connection connection, String lockName) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_SQL)) {
			statement.setQueryTimeout(networkTimeoutSeconds);
			statement.setString(1, lockName);
			statement.setInt(2, acquisitionTimeoutSeconds);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
					throw new PaymentOperationExecutionFenceUnavailableException();
				}
			}
		}
	}

	private void cleanupConnection(
		UUID operationUid,
		Connection connection,
		String lockName,
		boolean lockAcquired,
		boolean actionStarted,
		Throwable actionFailure
	) {
		if (connection == null) {
			return;
		}

		boolean forcePhysicalClose = !actionStarted;
		if (lockAcquired) {
			try {
				releaseMysqlLock(connection, lockName);
			} catch (SQLException | RuntimeException cleanupFailure) {
				recordCleanupFailure(
					operationUid, CleanupFailureType.RELEASE_LOCK, actionStarted, actionFailure);
				forcePhysicalClose = true;
			}
		}

		if (forcePhysicalClose) {
			forcePhysicalSessionClose(operationUid, connection, actionStarted, actionFailure);
		}

		try {
			connection.close();
		} catch (SQLException | RuntimeException cleanupFailure) {
			recordCleanupFailure(
				operationUid, CleanupFailureType.CLOSE_CONNECTION, actionStarted, actionFailure);
			if (!forcePhysicalClose) {
				forcePhysicalSessionClose(operationUid, connection, actionStarted, actionFailure);
			}
		}
	}

	private void forcePhysicalSessionClose(
		UUID operationUid,
		Connection connection,
		boolean actionStarted,
		Throwable actionFailure
	) {
		try {
			connection.abort(Runnable::run);
			return;
		} catch (SQLException | RuntimeException cleanupFailure) {
			recordCleanupFailure(
				operationUid, CleanupFailureType.ABORT_CONNECTION, actionStarted, actionFailure);
		}

		if (hikariDataSource == null) {
			return;
		}
		try {
			hikariDataSource.evictConnection(connection);
		} catch (RuntimeException cleanupFailure) {
			recordCleanupFailure(
				operationUid, CleanupFailureType.EVICT_CONNECTION, actionStarted, actionFailure);
		}
	}

	private void releaseMysqlLock(Connection connection, String lockName) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(RELEASE_SQL)) {
			statement.setQueryTimeout(networkTimeoutSeconds);
			statement.setString(1, lockName);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
					throw new SQLException("payment operation execution fence was not released");
				}
			}
		}
	}

	private void recordCleanupFailure(
		UUID operationUid,
		CleanupFailureType failureType,
		boolean actionStarted,
		Throwable actionFailure
	) {
		log.error(
			"Payment operation execution fence cleanup failed: operationUid={}, failureType={}",
			operationUid,
			failureType);
		if (actionStarted && actionFailure != null) {
			actionFailure.addSuppressed(new PaymentOperationExecutionFenceCleanupException(failureType));
		}
	}

	private static HikariDataSource findHikariDataSource(DataSource dataSource) {
		return DataSourceUnwrapper.unwrap(dataSource, HikariDataSource.class);
	}

	private static void validateMaxConcurrency(
		int maxConcurrency,
		HikariDataSource hikariDataSource
	) {
		if (maxConcurrency <= 0) {
			throw new IllegalArgumentException("execution fence maxConcurrency must be positive");
		}
		long requiredPoolSize = (long) maxConcurrency * 2;
		int maximumPoolSize = hikariDataSource == null
			? 0
			: hikariDataSource.getMaximumPoolSize();
		if (hikariDataSource != null && maximumPoolSize <= 0) {
			maximumPoolSize = DEFAULT_HIKARI_MAXIMUM_POOL_SIZE;
		}
		if (hikariDataSource != null
			&& requiredPoolSize > maximumPoolSize) {
			throw new IllegalArgumentException(
				"execution fence must reserve one Hikari transaction connection per permit");
		}
	}

	private static int timeoutSeconds(Duration timeout, String name) {
		Objects.requireNonNull(timeout, name + " must not be null");
		if (timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		if (timeout.compareTo(Duration.ofSeconds(Integer.MAX_VALUE)) > 0) {
			throw new IllegalArgumentException(name + " exceeds JDBC seconds limit");
		}
		long seconds = timeout.getSeconds() + (timeout.getNano() == 0 ? 0 : 1);
		return Math.toIntExact(seconds);
	}

	private static int timeoutMillis(Duration timeout) {
		if (timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
			throw new IllegalArgumentException("networkTimeout exceeds JDBC milliseconds limit");
		}
		long millis = timeout.toMillis();
		if (timeout.getNano() % 1_000_000 != 0) {
			millis++;
		}
		return Math.toIntExact(millis);
	}

	private static void validateNetworkTimeout(
		int acquisitionTimeoutSeconds,
		int networkTimeoutSeconds,
		int networkTimeoutMillis
	) {
		long acquisitionTimeoutMillis = (long) acquisitionTimeoutSeconds * 1_000;
		if (networkTimeoutSeconds <= acquisitionTimeoutSeconds
			|| networkTimeoutMillis <= acquisitionTimeoutMillis) {
			throw new IllegalArgumentException(
				"networkTimeout must exceed the effective MySQL lock wait");
		}
	}

	private enum CleanupFailureType {
		RELEASE_LOCK,
		ABORT_CONNECTION,
		EVICT_CONNECTION,
		CLOSE_CONNECTION
	}

	private static final class PaymentOperationExecutionFenceCleanupException
		extends RuntimeException {

		private PaymentOperationExecutionFenceCleanupException(CleanupFailureType failureType) {
			super("payment operation execution fence cleanup failed: " + failureType);
		}
	}
}
