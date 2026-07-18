package kr.kro.airbob.domain.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyPreparedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.exception.CouponStockPreparationNotAllowedException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponStockPreparationService {

	private static final long RETENTION_DAYS_AFTER_ISSUE_END = 7;

	private final CouponRepository couponRepository;
	private final MemberCouponRepository memberCouponRepository;
	private final CouponRedisStockManager stockManager;
	private final CouponTimeProvider timeProvider;

	@Transactional
	public void prepare(Long couponId) {
		Coupon coupon = couponRepository.findByIdForUpdate(couponId)
			.orElseThrow(CouponNotFoundException::new);
		long actualIssuedCount = memberCouponRepository.countByCouponId(couponId);
		LocalDateTime now = timeProvider.now();

		if (!canPrepare(coupon, actualIssuedCount, now)) {
			throw new CouponStockPreparationNotAllowedException();
		}

		CouponRedisPreparationResult result = stockManager.prepare(
			couponId,
			coupon.getTotalQuantity(),
			timeProvider.toEpochMilli(coupon.getIssueStartAt()),
			timeProvider.toEpochMilli(coupon.getIssueEndAt()),
			true,
			timeProvider.toEpochMilli(
				coupon.getIssueEndAt().plusDays(RETENTION_DAYS_AFTER_ISSUE_END)));

		if (result == CouponRedisPreparationResult.ALREADY_PREPARED) {
			throw new CouponAlreadyPreparedException();
		}
	}

	private boolean canPrepare(Coupon coupon, long actualIssuedCount, LocalDateTime now) {
		Integer totalQuantity = coupon.getTotalQuantity();
		return totalQuantity != null
			&& totalQuantity > 0
			&& Boolean.TRUE.equals(coupon.getIsActive())
			&& now.isBefore(coupon.getIssueStartAt())
			&& coupon.getIssueEndAt().isAfter(coupon.getIssueStartAt())
			&& coupon.getIssuedQuantity() == 0
			&& actualIssuedCount == 0;
	}
}
