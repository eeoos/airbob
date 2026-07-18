package kr.kro.airbob.domain.coupon.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.coupon.dto.CouponRequest;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyPreparedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponService {

	private final CouponRepository couponRepository;
	private final CouponRedisStockManager stockManager;

	@Transactional(readOnly = true)
	public CouponResponse.CouponInfos findValidCoupons() {
		List<Coupon> coupons = couponRepository.findByIsActiveTrue();
		List<CouponResponse.CouponInfo> infos = coupons.stream()
			.map(CouponResponse.CouponInfo::of)
			.toList();

		return new CouponResponse.CouponInfos(infos);
	}

	@Transactional
	public void createCoupon(CouponRequest.Create dto) {
		couponRepository.save(Coupon.of(dto));
	}

	@Transactional
	public void updateCoupon(CouponRequest.Update dto, Long couponId) {
		Coupon coupon = couponRepository.findByIdForUpdate(couponId)
			.orElseThrow(CouponNotFoundException::new);

		if (stockManager.isPrepared(couponId) && coupon.changesPreparedIssuanceConfiguration(dto)) {
			throw new CouponAlreadyPreparedException();
		}
		coupon.updateWithDto(dto);
	}

	@Transactional
	public void deleteCoupon(Long couponId) {
		Coupon coupon = couponRepository.findByIdForUpdate(couponId)
			.orElseThrow(CouponNotFoundException::new);

		if (stockManager.isPrepared(couponId)) {
			throw new CouponAlreadyPreparedException();
		}
		coupon.deactivate();
	}
}
