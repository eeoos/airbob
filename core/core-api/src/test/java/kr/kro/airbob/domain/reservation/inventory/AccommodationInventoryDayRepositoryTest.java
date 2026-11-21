package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

@DisplayName("AccommodationInventoryDayRepository error classification")
class AccommodationInventoryDayRepositoryTest {

	@Test
	@DisplayName("only MySQL ER_LOCK_NOWAIT with HY000 is a retriable inventory busy result")
	void classifiesOnlyMysqlNowait() {
		assertThat(MysqlNowaitFailureClassifier.isNowait(
			wrapped(new SQLException("nowait", "HY000", 3572)))).isTrue();
		assertThat(MysqlNowaitFailureClassifier.isNowait(
			wrapped(new SQLException("same vendor code, wrong state", "40001", 3572)))).isFalse();
		assertThat(MysqlNowaitFailureClassifier.isNowait(
			wrapped(new SQLException("lock wait timeout", "HY000", 1205)))).isFalse();
		assertThat(MysqlNowaitFailureClassifier.isNowait(
			wrapped(new SQLException("deadlock", "40001", 1213)))).isFalse();
	}

	private DataAccessResourceFailureException wrapped(SQLException cause) {
		return new DataAccessResourceFailureException("database operation failed", cause);
	}
}
