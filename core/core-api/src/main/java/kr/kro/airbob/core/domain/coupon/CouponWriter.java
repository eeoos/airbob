package kr.kro.airbob.core.domain.coupon;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.core.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;

/** 쿠폰 정책과 발급 재고(coupon_issue_unit) 변경 구현 컴포넌트. 정책 잠금은 호출하는 쪽이 먼저 잡는다. */
@Component
@RequiredArgsConstructor
public class CouponWriter {

	private final CouponRepository couponRepository;
	private final CouponIssueUnitStore units;

	public Long create(Coupon coupon) {
		return couponRepository.save(coupon).getId();
	}

	public void delete(Coupon coupon) {
		couponRepository.delete(coupon);
	}

	/** 재고 번호 first..last를 추가한다. */
	public void addUnits(long couponId, int first, int last) {
		units.insertRange(couponId, first, last);
	}

	/** target보다 큰 번호의 남은 재고를 지우고 지운 건수를 반환한다. */
	public int removeUnitsAbove(long couponId, int target) {
		return units.removeAbove(couponId, target);
	}
}
