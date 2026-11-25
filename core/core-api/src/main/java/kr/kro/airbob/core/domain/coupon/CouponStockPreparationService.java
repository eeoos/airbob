package kr.kro.airbob.core.domain.coupon;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponStockPreparationService {
	private final CouponReader couponReader;
	private final CouponWriter couponWriter;

	@Transactional
	public void prepare(Long couponId) {
		var coupon = couponReader.readForUpdate(couponId);
		var now = couponReader.readDatabaseNow();
		if (coupon.isStockPrepared())
			throw new CouponAlreadyPreparedException();
		if (!Boolean.TRUE.equals(coupon.getIsActive()) || !now.isBefore(coupon.getIssueStartAt())
			|| couponReader.countIssued(couponId) != 0) {
			throw new CouponStockPreparationNotAllowedException();
		}
		coupon.validateConfiguration();
		couponWriter.addUnits(couponId, 1, coupon.getTotalQuantity());
		coupon.markStockPrepared(now);
	}
}
