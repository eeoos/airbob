package kr.kro.airbob.domain.reservation.inventory;

import java.sql.SQLException;

public final class MysqlNowaitFailureClassifier {
	private static final int MYSQL_NOWAIT_ERROR = 3572;
	private static final String MYSQL_GENERAL_ERROR_STATE = "HY000";

	private MysqlNowaitFailureClassifier() {
	}

	public static boolean isNowait(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof SQLException sqlException
				&& sqlException.getErrorCode() == MYSQL_NOWAIT_ERROR
				&& MYSQL_GENERAL_ERROR_STATE.equals(sqlException.getSQLState())) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
