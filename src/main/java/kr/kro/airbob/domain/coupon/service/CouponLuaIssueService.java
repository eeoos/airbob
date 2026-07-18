package kr.kro.airbob.domain.coupon.service;

import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotIssuableException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.exception.CouponStockNotPreparedException;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricRecorder;
import kr.kro.airbob.domain.coupon.monitoring.CouponIssueMetricResultResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponLuaIssueService {

	private final CouponRedisStockManager stockManager;
	private final CouponIssueTransactionService transactionService;
	private final CouponIssueMetricRecorder metricRecorder;

	public void issue(Long couponId, Long memberId) {
		long issueStartedAt = System.nanoTime();
		CouponIssueMetricRecorder.IssueResult issueResult = CouponIssueMetricRecorder.IssueResult.ERROR;
		try {
			CouponRedisIssueResult redisResult = stockManager.issue(couponId, memberId);
			if (!redisResult.isApproved()) {
				throw rejectionFor(redisResult.status());
			}

			persistApprovedIssue(couponId, memberId);
			issueResult = CouponIssueMetricRecorder.IssueResult.SUCCESS;
		} catch (RuntimeException exception) {
			issueResult = CouponIssueMetricResultResolver.issueResult(exception);
			throw exception;
		} finally {
			metricRecorder.recordIssue(
				CouponIssueMetricRecorder.Strategy.LUA,
				issueResult,
				System.nanoTime() - issueStartedAt);
		}
	}

	private void persistApprovedIssue(Long couponId, Long memberId) {
		long databaseStartedAt = System.nanoTime();
		try {
			transactionService.persistApprovedIssue(couponId, memberId);
			metricRecorder.recordDatabase(
				CouponIssueMetricRecorder.Strategy.LUA,
				CouponIssueMetricRecorder.DatabaseResult.SUCCESS,
				System.nanoTime() - databaseStartedAt);
		} catch (RuntimeException databaseFailure) {
			metricRecorder.recordDatabase(
				CouponIssueMetricRecorder.Strategy.LUA,
				CouponIssueMetricRecorder.DatabaseResult.ERROR,
				System.nanoTime() - databaseStartedAt);
			compensateWithoutMasking(couponId, memberId, databaseFailure);
			throw databaseFailure;
		}
	}

	private RuntimeException rejectionFor(CouponRedisIssueStatus status) {
		return switch (status) {
			case SOLD_OUT -> new CouponSoldOutException();
			case DUPLICATE -> new CouponAlreadyIssuedException();
			case NOT_STARTED, ENDED, INACTIVE -> new CouponNotIssuableException();
			case UNPREPARED -> new CouponStockNotPreparedException();
			case APPROVED -> throw new IllegalArgumentException("승인 결과는 거절 예외로 변환할 수 없습니다.");
		};
	}

	private void compensateWithoutMasking(Long couponId, Long memberId, RuntimeException databaseFailure) {
		try {
			CouponRedisCompensationResult result = stockManager.compensate(couponId, memberId);
			metricRecorder.recordCompensation(compensationMetricResult(result));
			if (result != CouponRedisCompensationResult.COMPENSATED) {
				log.error("쿠폰 Redis 보상이 완료되지 않음. result={}, couponId={}, memberId={}",
					result, couponId, memberId);
			}
		} catch (RuntimeException compensationFailure) {
			metricRecorder.recordCompensation(CouponIssueMetricRecorder.CompensationResult.ERROR);
			databaseFailure.addSuppressed(compensationFailure);
			log.error("쿠폰 Redis 보상 중 예외. couponId={}, memberId={}",
				couponId, memberId, compensationFailure);
		}
	}

	private CouponIssueMetricRecorder.CompensationResult compensationMetricResult(
		CouponRedisCompensationResult result
	) {
		return switch (result) {
			case COMPENSATED -> CouponIssueMetricRecorder.CompensationResult.COMPENSATED;
			case NO_OP -> CouponIssueMetricRecorder.CompensationResult.NO_OP;
			case META_MISSING -> CouponIssueMetricRecorder.CompensationResult.META_MISSING;
		};
	}
}
