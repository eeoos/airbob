package kr.kro.airbob.domain.coupon.service;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;

import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.RetryReason;

final class CouponIssueFailureClassifier {
	private CouponIssueFailureClassifier() { }

	static boolean duplicateMemberCoupon(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				String constraint = violation.getConstraintName();
				if ("uk_member_coupon".equals(constraint) || "member_coupon.uk_member_coupon".equals(constraint)) {
					return violation.getErrorCode() == 1062;
				}
			}
		}
		return false;
	}

	static RetryReason retryReason(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql) {
				return switch (sql.getErrorCode()) {
					case 1213 -> RetryReason.DEADLOCK;
					case 1205 -> RetryReason.LOCK_TIMEOUT;
					case 3572 -> RetryReason.POLICY_LOCK_BUSY;
					default -> null;
				};
			}
		}
		return null;
	}
}
