package kr.kro.airbob.domain.coupon.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponIssueContendedException;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.DatabaseResult;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.IssueResult;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.RetryReason;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricResultResolver;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CouponSkipLockedIssueService {
	private final PlatformTransactionManager transactionManager;
	private final CouponIssuer issuer;
	private final CouponIssueMetricRecorder metrics;
	private final int maxAttempts;
	private final long retryBudgetNanos;

	public CouponSkipLockedIssueService(PlatformTransactionManager transactionManager, CouponIssuer issuer,
		CouponIssueMetricRecorder metrics, @Value("${coupon.issue.max-attempts:3}") int maxAttempts,
		@Value("${coupon.issue.retry-budget-ms:250}") long retryBudgetMillis) {
		Assert.isTrue(maxAttempts >= 1 && maxAttempts <= 10, "coupon.issue.max-attempts must be 1..10");
		Assert.isTrue(retryBudgetMillis >= 1 && retryBudgetMillis <= 5000, "Invalid coupon retry budget");
		this.transactionManager = transactionManager;
		this.issuer = issuer;
		this.metrics = metrics;
		this.maxAttempts = maxAttempts;
		this.retryBudgetNanos = TimeUnit.MILLISECONDS.toNanos(retryBudgetMillis);
	}

	public void issue(Long couponId, Long memberId) {
		long started = System.nanoTime();
		IssueResult result = IssueResult.ERROR;
		try {
			for (int attempt = 1; attempt <= maxAttempts; attempt++) {
				RetryReason retry;
				try {
					attempt(couponId, memberId);
					result = IssueResult.SUCCESS;
					return;
				} catch (CouponIssuer.NoAvailableUnitException busy) {
					retry = RetryReason.EMPTY_UNIT;
				} catch (RuntimeException failure) {
					if (CouponIssueFailureClassifier.duplicateMemberCoupon(failure)) throw new CouponAlreadyIssuedException();
					retry = CouponIssueFailureClassifier.retryReason(failure);
					if (retry == null) throw failure;
				}
				// executeWithoutResult has rolled the entire attempt back before this fresh snapshot.
				metrics.recordRetry(retry);
				classifyEmptyOrContended(couponId, memberId);
				long remaining = retryBudgetNanos - (System.nanoTime() - started);
				if (attempt == maxAttempts || remaining <= 0) throw new CouponIssueContendedException();
				LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(ThreadLocalRandom.current().nextLong(1, 6))));
				if (Thread.currentThread().isInterrupted() || System.nanoTime() - started >= retryBudgetNanos) {
					throw new CouponIssueContendedException();
				}
			}
		} catch (RuntimeException failure) {
			result = CouponIssueMetricResultResolver.issueResult(failure);
			throw failure;
		} finally {
			metrics.recordIssue(result, System.nanoTime() - started);
		}
	}

	private void attempt(Long couponId, Long memberId) {
		long started = System.nanoTime();
		DatabaseResult result = DatabaseResult.ERROR;
		try {
			transaction(false).executeWithoutResult(status -> issuer.issue(couponId, memberId));
			result = DatabaseResult.SUCCESS;
		} catch (RuntimeException failure) {
			result = failure instanceof CouponIssuer.NoAvailableUnitException || CouponIssueFailureClassifier.retryReason(failure) != null
				? DatabaseResult.RETRY : CouponIssueMetricResultResolver.databaseResult(failure);
			throw failure;
		} finally {
			metrics.recordDatabase(result, System.nanoTime() - started);
		}
	}

	private void classifyEmptyOrContended(Long couponId, Long memberId) {
		transaction(true).executeWithoutResult(status -> issuer.classifyFailedAttempt(couponId, memberId));
	}

	private TransactionTemplate transaction(boolean readOnly) {
		var template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		template.setReadOnly(readOnly);
		template.setTimeout(1);
		return template;
	}
}
