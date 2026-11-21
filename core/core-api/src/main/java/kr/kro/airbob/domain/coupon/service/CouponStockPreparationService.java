package kr.kro.airbob.domain.coupon.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.coupon.exception.CouponAlreadyPreparedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.exception.CouponStockPreparationNotAllowedException;
import kr.kro.airbob.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponStockPreparationService {
	private final CouponRepository couponRepository;
	private final MemberCouponRepository memberCouponRepository;
	private final CouponIssueUnitStore units;

	@Transactional
	public void prepare(Long couponId) {
		var coupon = couponRepository.findByIdForUpdate(couponId).orElseThrow(CouponNotFoundException::new);
		var now = units.databaseNow();
		if (coupon.isStockPrepared()) throw new CouponAlreadyPreparedException();
		if (!Boolean.TRUE.equals(coupon.getIsActive()) || !now.isBefore(coupon.getIssueStartAt())
			|| memberCouponRepository.countByCouponId(couponId) != 0) {
			throw new CouponStockPreparationNotAllowedException();
		}
		coupon.validateConfiguration();
		units.insertRange(couponId, 1, coupon.getTotalQuantity());
		coupon.markStockPrepared(now);
	}
}
