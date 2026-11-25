package kr.kro.airbob.core.domain.coupon.monitoring;

import kr.kro.airbob.core.domain.coupon.CouponAlreadyIssuedException;
import kr.kro.airbob.core.domain.coupon.CouponIssueContendedException;
import kr.kro.airbob.core.domain.coupon.CouponNotIssuableException;
import kr.kro.airbob.core.domain.coupon.CouponSoldOutException;
import kr.kro.airbob.core.domain.coupon.CouponStockNotPreparedException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CouponIssueMetricResultResolver {

	public static CouponIssueMetricRecorder.IssueResult issueResult(RuntimeException exception) {
		if (exception instanceof CouponIssueContendedException)
			return CouponIssueMetricRecorder.IssueResult.CONTENDED;
		if (exception instanceof CouponSoldOutException) {
			return CouponIssueMetricRecorder.IssueResult.SOLD_OUT;
		}
		if (exception instanceof CouponAlreadyIssuedException) {
			return CouponIssueMetricRecorder.IssueResult.DUPLICATE;
		}
		if (exception instanceof CouponNotIssuableException) {
			return CouponIssueMetricRecorder.IssueResult.NOT_ISSUABLE;
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
