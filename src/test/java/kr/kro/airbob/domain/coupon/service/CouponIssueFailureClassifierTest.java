package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.*;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.RetryReason;

class CouponIssueFailureClassifierTest {
	@Test
	void onlyTheMemberCouponUniqueConstraintMeansAlreadyIssued() {
		assertThat(CouponIssueFailureClassifier.duplicateMemberCoupon(
			violation(1062, "member_coupon.uk_member_coupon"))).isTrue();
		assertThat(CouponIssueFailureClassifier.duplicateMemberCoupon(violation(1062, "uk_member_coupon"))).isTrue();
		assertThat(CouponIssueFailureClassifier.duplicateMemberCoupon(violation(1062, "some_other_unique_key"))).isFalse();
		assertThat(CouponIssueFailureClassifier.duplicateMemberCoupon(violation(1452, "member_fk"))).isFalse();
	}

	@Test
	void recognizesOnlyKnownRollbackSafeMysqlLockFailures() {
		assertThat(CouponIssueFailureClassifier.retryReason(sql(1213))).isEqualTo(RetryReason.DEADLOCK);
		assertThat(CouponIssueFailureClassifier.retryReason(sql(1205))).isEqualTo(RetryReason.LOCK_TIMEOUT);
		assertThat(CouponIssueFailureClassifier.retryReason(sql(3572))).isEqualTo(RetryReason.POLICY_LOCK_BUSY);
		assertThat(CouponIssueFailureClassifier.retryReason(sql(1452))).isNull();
		assertThat(CouponIssueFailureClassifier.retryReason(new RuntimeException(
			new SQLException("commit outcome unknown", "08007", 0)))).isNull();
	}

	private RuntimeException violation(int code, String constraint) {
		return new RuntimeException(new ConstraintViolationException("constraint", new SQLException("", "23000", code), constraint));
	}
	private RuntimeException sql(int code) { return new RuntimeException(new SQLException("", "40001", code)); }
}
