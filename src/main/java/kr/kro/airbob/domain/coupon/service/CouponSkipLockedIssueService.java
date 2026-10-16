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

import kr.kro.airbob.domain.coupon.common.CouponIssueStrategy;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponIssueContendedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.exception.CouponNotIssuableException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.exception.CouponStockNotPreparedException;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.DatabaseResult;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.IssueResult;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.RetryReason;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder.Strategy;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricResultResolver;
import kr.kro.airbob.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CouponSkipLockedIssueService {
	private final PlatformTransactionManager transactionManager;
	private final CouponRepository coupons;
	private final CouponIssueUnitStore units;
	private final MemberCouponRepository memberCoupons;
	private final MemberRepository members;
	private final CouponIssueMetricRecorder metrics;
	private final int maxAttempts;
	private final long retryBudgetNanos;

	public CouponSkipLockedIssueService(PlatformTransactionManager transactionManager, CouponRepository coupons,
		CouponIssueUnitStore units, MemberCouponRepository memberCoupons, MemberRepository members,
		CouponIssueMetricRecorder metrics, @Value("${coupon.issue.max-attempts:3}") int maxAttempts,
		@Value("${coupon.issue.retry-budget-ms:250}") long retryBudgetMillis) {
		Assert.isTrue(maxAttempts >= 1 && maxAttempts <= 10, "coupon.issue.max-attempts must be 1..10");
		Assert.isTrue(retryBudgetMillis >= 1 && retryBudgetMillis <= 5000, "Invalid coupon retry budget");
		this.transactionManager = transactionManager;
		this.coupons = coupons;
		this.units = units;
		this.memberCoupons = memberCoupons;
		this.members = members;
		this.metrics = metrics;
		this.maxAttempts = maxAttempts;
		this.retryBudgetNanos = TimeUnit.MILLISECONDS.toNanos(retryBudgetMillis);
	}

	public void issue(Long couponId, Long memberId) {
		// End routing before an attempt begins. A query in NOT_SUPPORTED synchronization
		// can retain an EntityManager connection while REQUIRES_NEW requests a second one.
		CouponIssueStrategy strategy = transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, true)
			.execute(status -> units.strategy(couponId));
		issue(couponId, memberId, strategy);
	}

	public void issue(Long couponId, Long memberId, CouponIssueStrategy strategy) {
		if (strategy == CouponIssueStrategy.DB) throw new CouponNotIssuableException();
		long started = System.nanoTime();
		Strategy metricStrategy = Strategy.valueOf(strategy.name());
		IssueResult result = IssueResult.ERROR;
		try {
			for (int attempt = 1; attempt <= maxAttempts; attempt++) {
				RetryReason retry;
				try {
					attempt(couponId, memberId, strategy, metricStrategy);
					result = IssueResult.SUCCESS;
					return;
				} catch (NoAvailableUnitException busy) {
					retry = RetryReason.EMPTY_UNIT;
				} catch (RuntimeException failure) {
					if (CouponIssueFailureClassifier.duplicateMemberCoupon(failure)) throw new CouponAlreadyIssuedException();
					retry = CouponIssueFailureClassifier.retryReason(failure);
					if (retry == null) throw failure;
				}
				// executeWithoutResult has rolled the entire attempt back before this fresh snapshot.
				metrics.recordRetry(metricStrategy, retry);
				classifyEmptyOrContended(couponId, memberId, strategy);
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
			metrics.recordIssue(metricStrategy, result, System.nanoTime() - started);
		}
	}

	private void attempt(Long couponId, Long memberId, CouponIssueStrategy strategy, Strategy metricStrategy) {
		long started = System.nanoTime();
		DatabaseResult result = DatabaseResult.ERROR;
		try {
			transaction(strategy.isolation(), false).executeWithoutResult(status -> {
				// OSIV can retain first-level entities even across newly started transactions.
				// The locked policy read must reflect this attempt's database state.
				units.clearPersistenceContext();
				Coupon coupon = coupons.findByIdForShareNowait(couponId).orElseThrow(CouponNotFoundException::new);
				validate(coupon, strategy);
				if (memberCoupons.existsByMemberIdAndCouponId(memberId, couponId)) throw new CouponAlreadyIssuedException();
				var unit = units.lockAvailable(couponId, strategy).orElseThrow(NoAvailableUnitException::new);
				units.consume(couponId, strategy, unit);
				memberCoupons.saveAndFlush(MemberCoupon.issue(members.getReferenceById(memberId), coupon));
			});
			result = DatabaseResult.SUCCESS;
		} catch (RuntimeException failure) {
			result = failure instanceof NoAvailableUnitException || CouponIssueFailureClassifier.retryReason(failure) != null
				? DatabaseResult.RETRY : CouponIssueMetricResultResolver.databaseResult(failure);
			throw failure;
		} finally {
			metrics.recordDatabase(metricStrategy, result, System.nanoTime() - started);
		}
	}

	private void classifyEmptyOrContended(Long couponId, Long memberId, CouponIssueStrategy strategy) {
		transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ, true).executeWithoutResult(status -> {
			units.clearPersistenceContext();
			if (memberCoupons.existsByMemberIdAndCouponId(memberId, couponId)) throw new CouponAlreadyIssuedException();
			Coupon coupon = coupons.findById(couponId).orElseThrow(CouponNotFoundException::new);
			validate(coupon, strategy);
			if (!units.hasRemaining(couponId, strategy)) throw new CouponSoldOutException();
		});
	}

	private void validate(Coupon coupon, CouponIssueStrategy strategy) {
		if (coupon.getIssueStrategy() != strategy || coupon.getTotalQuantity() == null || coupon.getTotalQuantity() < 1
			|| !Boolean.TRUE.equals(coupon.getIsActive()) || !coupon.isIssueOpen(units.databaseNow())) {
			throw new CouponNotIssuableException();
		}
		if (!coupon.isStockPrepared()) throw new CouponStockNotPreparedException();
	}

	private TransactionTemplate transaction(int isolation, boolean readOnly) {
		var template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.setIsolationLevel(isolation);
		template.setReadOnly(readOnly);
		template.setTimeout(1);
		return template;
	}

	private static final class NoAvailableUnitException extends RuntimeException { }
}
