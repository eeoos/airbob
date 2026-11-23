package kr.kro.airbob.core.domain.coupon;

import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.core.support.cursor.dto.CursorResponse;

/** 회원이 보유한 쿠폰 목록 한 페이지. 보유 상태는 애플리케이션 시각으로 계산한다. */
public record MemberCouponPage(
	List<Item> coupons,
	CursorResponse.PageInfo pageInfo
) {

	public record Item(MemberCouponProjection coupon, MemberCouponStatus status) {

		public static Item of(MemberCouponProjection coupon, LocalDateTime now) {
			return new Item(coupon, MemberCouponStatus.resolve(
				coupon.used(), coupon.active(), coupon.usableFrom(), coupon.usableUntil(), now));
		}
	}
}
