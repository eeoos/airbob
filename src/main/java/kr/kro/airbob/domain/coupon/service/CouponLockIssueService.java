package kr.kro.airbob.domain.coupon.service;

import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponLockIssueService {

	private final CouponIssueTransactionService transactionService;
	private final CouponLockManager lockManager;

	public void issue(Long couponId, Long memberId) {
		RLock lock = lockManager.acquireLock(couponId);
		try {
			transactionService.issueUnderLock(couponId, memberId);
		} finally {
			lockManager.releaseLock(lock);
		}
	}
}
