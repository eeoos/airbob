package kr.kro.airbob.domain.coupon.service;

import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricResultResolver;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponLockIssueService {

	private final CouponIssueTransactionService transactionService;
	private final CouponLockManager lockManager;
	private final CouponIssueMetricRecorder metricRecorder;

	public void issue(Long couponId, Long memberId) {
		long issueStartedAt = System.nanoTime();
		CouponIssueMetricRecorder.IssueResult issueResult = CouponIssueMetricRecorder.IssueResult.ERROR;
		try {
			RLock lock = lockManager.acquireLock(couponId);
			try {
				issueDatabaseTransaction(couponId, memberId);
				issueResult = CouponIssueMetricRecorder.IssueResult.SUCCESS;
			} finally {
				lockManager.releaseLock(lock);
			}
		} catch (RuntimeException exception) {
			issueResult = CouponIssueMetricResultResolver.issueResult(exception);
			throw exception;
		} finally {
			metricRecorder.recordIssue(
				CouponIssueMetricRecorder.Strategy.LOCK,
				issueResult,
				System.nanoTime() - issueStartedAt);
		}
	}

	private void issueDatabaseTransaction(Long couponId, Long memberId) {
		long databaseStartedAt = System.nanoTime();
		CouponIssueMetricRecorder.DatabaseResult databaseResult = CouponIssueMetricRecorder.DatabaseResult.ERROR;
		try {
			transactionService.issueUnderLock(couponId, memberId);
			databaseResult = CouponIssueMetricRecorder.DatabaseResult.SUCCESS;
		} catch (RuntimeException exception) {
			databaseResult = CouponIssueMetricResultResolver.databaseResult(exception);
			throw exception;
		} finally {
			metricRecorder.recordDatabase(
				CouponIssueMetricRecorder.Strategy.LOCK,
				databaseResult,
				System.nanoTime() - databaseStartedAt);
		}
	}
}
