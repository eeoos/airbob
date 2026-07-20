package kr.kro.airbob.domain.coupon.monitoring;

import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponLockTimeoutException;
import kr.kro.airbob.domain.coupon.exception.CouponNotIssuableException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.exception.CouponStockNotPreparedException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CouponIssueMetricResultResolver {

	public static CouponIssueMetricRecorder.IssueResult issueResult(RuntimeException exception) {
		if (exception instanceof CouponSoldOutException) {
			return CouponIssueMetricRecorder.IssueResult.SOLD_OUT;
		}
		if (exception instanceof CouponAlreadyIssuedException) {
			return CouponIssueMetricRecorder.IssueResult.DUPLICATE;
		}
		if (exception instanceof CouponNotIssuableException) {
			return CouponIssueMetricRecorder.IssueResult.NOT_ISSUABLE;
		}
		if (exception instanceof CouponLockTimeoutException) {
			return CouponIssueMetricRecorder.IssueResult.TIMEOUT;
		}
		if (exception instanceof CouponStockNotPreparedException) {
			return CouponIssueMetricRecorder.IssueResult.UNPREPARED;
		}
		return CouponIssueMetricRecorder.IssueResult.ERROR;
	}

	public static CouponIssueMetricRecorder.DatabaseResult databaseResult(RuntimeException exception) {
		if (exception instanceof CouponSoldOutException
			|| exception instanceof CouponAlreadyIssuedException
			|| exception instanceof CouponNotIssuableException) {
			return CouponIssueMetricRecorder.DatabaseResult.REJECTED;
		}
		return CouponIssueMetricRecorder.DatabaseResult.ERROR;
	}
}
