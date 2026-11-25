package kr.kro.airbob.core.domain.coupon;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** API commands carry instants; entity values and database comparisons use UTC. */
@Service
@RequiredArgsConstructor
public class CouponService {
	private final CouponReader couponReader;
	private final CouponWriter couponWriter;

	@Transactional
	public Long createCoupon(NewCoupon dto) {
		var coupon = Coupon.of(dto);
		coupon.validateConfiguration();
		if (!couponReader.databaseNow().isBefore(coupon.getIssueStartAt()))
			throw new CouponInvalidConfigurationException();
		return couponWriter.create(coupon);
	}

	@Transactional
	public void updateCoupon(CouponPatch dto, Long couponId) {
		var coupon = couponReader.readForUpdate(couponId);
		var now = couponReader.databaseNow();
		if (coupon.changesPreparedIssuanceConfiguration(dto))
			requireBeforeStart(coupon, now);
		Integer previous = coupon.getTotalQuantity();
		coupon.update(dto);
		coupon.validateConfiguration();
		if (dto.issueStartAt() != null && !now.isBefore(coupon.getIssueStartAt())) {
			throw new CouponInvalidConfigurationException();
		}
		if (!Objects.equals(previous, coupon.getTotalQuantity()))
			adjustPreparedUnits(coupon, previous);
	}

	@Transactional
	public void changeQuantity(Long couponId, Integer quantity) {
		Coupon.validateQuantity(quantity);
		var coupon = couponReader.readForUpdate(couponId);
		requireBeforeStart(coupon, couponReader.databaseNow());
		Integer previous = coupon.getTotalQuantity();
		coupon.changeTotalQuantity(quantity);
		if (!Objects.equals(previous, quantity))
			adjustPreparedUnits(coupon, previous);
	}

	@Transactional
	public void deleteCoupon(Long couponId) {
		var coupon = couponReader.readForUpdate(couponId);
		requireBeforeStart(coupon, couponReader.databaseNow());
		if (coupon.isStockPrepared())
			couponWriter.removeUnitsAbove(couponId, 0);
		// Only an unstarted, never-issued campaign may be discarded.
		couponWriter.delete(coupon);
	}

	private void requireBeforeStart(Coupon coupon, LocalDateTime now) {
		if (!now.isBefore(coupon.getIssueStartAt())
			|| couponReader.countIssued(coupon.getId()) != 0) {
			throw new CouponConfigurationLockedException();
		}
	}

	private void adjustPreparedUnits(Coupon coupon, Integer previous) {
		if (!coupon.isStockPrepared())
			return;
		Coupon.validateQuantity(previous);
		int target = coupon.getTotalQuantity();
		if (target > previous) {
			couponWriter.addUnits(coupon.getId(), previous + 1, target);
		} else if (couponWriter.removeUnitsAbove(coupon.getId(), target) != previous - target) {
			throw new IllegalStateException("쿠폰 감량 건수가 저장된 총수량과 일치하지 않습니다.");
		}
	}
}
