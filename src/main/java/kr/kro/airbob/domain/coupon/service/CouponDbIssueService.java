package kr.kro.airbob.domain.coupon.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricResultResolver;
import lombok.RequiredArgsConstructor;

@Service
@Profile("coupon-benchmark")
@RequiredArgsConstructor
public class CouponDbIssueService {

	private final CouponIssueTransactionService transactionService;
	private final CouponIssueMetricRecorder metricRecorder;

	public void issue(Long couponId, Long memberId) {
		long issueStartedAt = System.nanoTime();
		CouponIssueMetricRecorder.IssueResult issueResult = CouponIssueMetricRecorder.IssueResult.ERROR;
		try {
			issueDatabaseTransaction(couponId, memberId);
			issueResult = CouponIssueMetricRecorder.IssueResult.SUCCESS;
		} catch (RuntimeException exception) {
			issueResult = CouponIssueMetricResultResolver.issueResult(exception);
			throw exception;
		} finally {
			metricRecorder.recordIssue(
				CouponIssueMetricRecorder.Strategy.DB,
				issueResult,
				System.nanoTime() - issueStartedAt);
		}
	}

	private void issueDatabaseTransaction(Long couponId, Long memberId) {
		long databaseStartedAt = System.nanoTime();
		CouponIssueMetricRecorder.DatabaseResult databaseResult = CouponIssueMetricRecorder.DatabaseResult.ERROR;
		try {
			transactionService.issueWithConditionalUpdate(couponId, memberId);
			databaseResult = CouponIssueMetricRecorder.DatabaseResult.SUCCESS;
		} catch (RuntimeException exception) {
			databaseResult = CouponIssueMetricResultResolver.databaseResult(exception);
			throw exception;
		} finally {
			metricRecorder.recordDatabase(
				CouponIssueMetricRecorder.Strategy.DB,
				databaseResult,
				System.nanoTime() - databaseStartedAt);
		}
	}
}
