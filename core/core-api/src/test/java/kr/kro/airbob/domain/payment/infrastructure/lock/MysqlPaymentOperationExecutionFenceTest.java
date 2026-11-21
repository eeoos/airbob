package kr.kro.airbob.domain.payment.infrastructure.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariDataSource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.kro.airbob.domain.payment.exception.PaymentOperationExecutionFenceUnavailableException;

class MysqlPaymentOperationExecutionFenceTest {

	private static final UUID OPERATION_UID =
		UUID.fromString("4eab8ec3-dd26-4dab-a3f4-a5cb8f5af98f");
	private static final String RAW_FAILURE = "raw-jdbc-failure-secret";

	private Logger logger;
	private ListAppender<ILoggingEvent> logAppender;

	@BeforeEach
	void captureLogs() {
		logger = (Logger) LoggerFactory.getLogger(MysqlPaymentOperationExecutionFence.class);
		logAppender = new ListAppender<>();
		logAppender.start();
		logger.addAppender(logAppender);
	}

	@AfterEach
	void stopCapturingLogs() {
		logger.detachAppender(logAppender);
		logAppender.stop();
	}

	@Test
	void releaseFailureAfterActionDoesNotReplaceTheDurableResultWithP007() throws Exception {
		JdbcDoubles jdbc = jdbcDoubles(true, false);
		MysqlPaymentOperationExecutionFence fence = fence(jdbc.dataSource());

		assertThat(fence.execute(OPERATION_UID, () -> "durable-result"))
			.isEqualTo("durable-result");

		then(jdbc.connection()).should().abort(any());
		then(jdbc.connection()).should().close();
		assertStableCleanupLog("RELEASE_LOCK");
	}

	@Test
	void closeFailureAfterActionDoesNotReplaceTheDurableResultWithP007() throws Exception {
		JdbcDoubles jdbc = jdbcDoubles(false, true);
		MysqlPaymentOperationExecutionFence fence = fence(jdbc.dataSource());

		assertThat(fence.execute(OPERATION_UID, () -> "committed-result"))
			.isEqualTo("committed-result");

		then(jdbc.connection()).should().abort(any());
		assertStableCleanupLog("CLOSE_CONNECTION");
	}

	@Test
	void actionFailureRemainsPrimaryAndReceivesStableSuppressedCleanup() throws Exception {
		JdbcDoubles jdbc = jdbcDoubles(true, false);
		MysqlPaymentOperationExecutionFence fence = fence(jdbc.dataSource());
		ExpectedActionFailure actionFailure = new ExpectedActionFailure();

		Throwable thrown = catchThrowable(() -> fence.execute(OPERATION_UID, () -> {
			throw actionFailure;
		}));

		assertThat(thrown).isSameAs(actionFailure);
		assertThat(thrown.getSuppressed()).hasSize(1);
		assertThat(thrown.getSuppressed()[0])
			.hasMessage("payment operation execution fence cleanup failed: RELEASE_LOCK")
			.hasNoCause();
		assertStableCleanupLog("RELEASE_LOCK");
	}

	@Test
	void connectionBorrowFailureReturnsP007AndReleasesTheLocalPermit() throws Exception {
		JdbcDoubles jdbc = jdbcDoubles(false, false);
		given(jdbc.dataSource().getConnection())
			.willThrow(new SQLException(RAW_FAILURE))
			.willReturn(jdbc.connection());
		MysqlPaymentOperationExecutionFence fence = fence(jdbc.dataSource());

		assertThatThrownBy(() -> fence.execute(OPERATION_UID, () -> "must-not-run"))
			.isInstanceOf(PaymentOperationExecutionFenceUnavailableException.class);
		assertThat(fence.execute(OPERATION_UID, () -> "next-action"))
			.isEqualTo("next-action");
	}

	@Test
	void maxConcurrentFencesMustReserveATransactionConnectionForEveryPermit() {
		try (HikariDataSource hikari = new HikariDataSource()) {
			hikari.setMaximumPoolSize(3);

			assertThatThrownBy(() -> new MysqlPaymentOperationExecutionFence(
				hikari, Duration.ofSeconds(1), Duration.ofSeconds(2), 2))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(
					"execution fence must reserve one Hikari transaction connection per permit");
		}
	}

	@Test
	void appliesADeadlineBeyondTheServerLockWaitToEveryFenceJdbcCall() throws Exception {
		JdbcDoubles jdbc = jdbcDoubles(false, false);
		MysqlPaymentOperationExecutionFence fence = fence(jdbc.dataSource());

		assertThat(fence.execute(OPERATION_UID, () -> "bounded")).isEqualTo("bounded");

		then(jdbc.connection()).should().setNetworkTimeout(any(), eq(2_000));
		then(jdbc.acquireStatement()).should().setQueryTimeout(2);
		then(jdbc.releaseStatement()).should().setQueryTimeout(2);
	}

	@Test
	void networkDeadlineMustExceedTheRoundedMysqlLockWait() {
		DataSource dataSource = mock(DataSource.class);

		assertThatThrownBy(() -> new MysqlPaymentOperationExecutionFence(
			dataSource, Duration.ofMillis(500), Duration.ofMillis(600), 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("networkTimeout must exceed the effective MySQL lock wait");
	}

	private MysqlPaymentOperationExecutionFence fence(DataSource dataSource) {
		return new MysqlPaymentOperationExecutionFence(
			dataSource, Duration.ofSeconds(1), Duration.ofSeconds(2), 1);
	}

	private JdbcDoubles jdbcDoubles(boolean releaseFails, boolean closeFails) throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement acquireStatement = mock(PreparedStatement.class);
		ResultSet acquireResult = successfulResult();
		PreparedStatement releaseStatement = mock(PreparedStatement.class);
		given(dataSource.getConnection()).willReturn(connection);
		given(connection.prepareStatement("SELECT GET_LOCK(?, ?)")).willReturn(acquireStatement);
		given(acquireStatement.executeQuery()).willReturn(acquireResult);
		given(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).willReturn(releaseStatement);
		if (releaseFails) {
			given(releaseStatement.executeQuery()).willThrow(new SQLException(RAW_FAILURE));
		} else {
			ResultSet releaseResult = successfulResult();
			given(releaseStatement.executeQuery()).willReturn(releaseResult);
		}
		if (closeFails) {
			willThrow(new SQLException(RAW_FAILURE)).given(connection).close();
		}
		return new JdbcDoubles(
			dataSource, connection, acquireStatement, releaseStatement);
	}

	private ResultSet successfulResult() throws SQLException {
		ResultSet result = mock(ResultSet.class);
		given(result.next()).willReturn(true);
		given(result.getInt(1)).willReturn(1);
		given(result.wasNull()).willReturn(false);
		return result;
	}

	private void assertStableCleanupLog(String failureType) {
		assertThat(logAppender.list)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anySatisfy(message -> assertThat(message)
				.contains(OPERATION_UID.toString(), failureType)
				.doesNotContain(RAW_FAILURE));
		assertThat(logAppender.list)
			.allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
	}

	private record JdbcDoubles(
		DataSource dataSource,
		Connection connection,
		PreparedStatement acquireStatement,
		PreparedStatement releaseStatement
	) {
	}

	private static final class ExpectedActionFailure extends RuntimeException {
	}
}
