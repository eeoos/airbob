package kr.kro.airbob.domain.coupon.service;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.coupon.dto.CouponRequest;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.exception.CouponConfigurationLockedException;
import kr.kro.airbob.domain.coupon.exception.CouponInvalidConfigurationException;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import lombok.RequiredArgsConstructor;

/** API commands carry instants; entity values and database comparisons use UTC. */
@Service
@RequiredArgsConstructor
public class CouponService {
	private final CouponRepository couponRepository;
	private final MemberCouponRepository memberCouponRepository;
	private final CouponIssueUnitStore units;

	@Transactional
	public void createCoupon(CouponRequest.Create dto) {
		var coupon = Coupon.of(dto);
		coupon.validateConfiguration();
		if (!units.databaseNow().isBefore(coupon.getIssueStartAt())) throw new CouponInvalidConfigurationException();
		couponRepository.save(coupon);
	}

	@Transactional
	public void updateCoupon(CouponRequest.Update dto, Long couponId) {
		var coupon = lock(couponId);
		var now = units.databaseNow();
		if (coupon.changesPreparedIssuanceConfiguration(dto)) requireBeforeStart(coupon, now);
		Integer previous = coupon.getTotalQuantity();
		coupon.updateWithDto(dto);
		coupon.validateConfiguration();
		if (dto.issueStartAt() != null && !now.isBefore(coupon.getIssueStartAt())) {
			throw new CouponInvalidConfigurationException();
		}
		if (!Objects.equals(previous, coupon.getTotalQuantity())) adjustPreparedUnits(coupon, previous);
	}

	@Transactional
	public void changeQuantity(Long couponId, Integer quantity) {
		Coupon.validateQuantity(quantity);
		var coupon = lock(couponId);
		requireBeforeStart(coupon, units.databaseNow());
		Integer previous = coupon.getTotalQuantity();
		coupon.changeTotalQuantity(quantity);
		if (!Objects.equals(previous, quantity)) adjustPreparedUnits(coupon, previous);
	}

	@Transactional
	public void deleteCoupon(Long couponId) {
		var coupon = lock(couponId);
		requireBeforeStart(coupon, units.databaseNow());
		if (coupon.isStockPrepared()) units.removeAbove(couponId, coupon.getIssueStrategy(), 0);
		// Only an unstarted, never-issued campaign may be discarded.
		couponRepository.delete(coupon);
	}

	private Coupon lock(Long id) {
		return couponRepository.findByIdForUpdate(id).orElseThrow(CouponNotFoundException::new);
	}

	private void requireBeforeStart(Coupon coupon, LocalDateTime now) {
		if (!now.isBefore(coupon.getIssueStartAt()) || coupon.getIssuedQuantity() != 0
			|| memberCouponRepository.countByCouponId(coupon.getId()) != 0) {
			throw new CouponConfigurationLockedException();
		}
	}

	private void adjustPreparedUnits(Coupon coupon, Integer previous) {
		if (!coupon.isStockPrepared()) return;
		Coupon.validateQuantity(previous);
		int target = coupon.getTotalQuantity();
		if (target > previous) {
			units.insertRange(coupon.getId(), coupon.getIssueStrategy(), previous + 1, target);
		} else if (units.removeAbove(coupon.getId(), coupon.getIssueStrategy(), target) != previous - target) {
			throw new IllegalStateException("쿠폰 감량 건수가 저장된 총수량과 일치하지 않습니다.");
		}
	}
}
