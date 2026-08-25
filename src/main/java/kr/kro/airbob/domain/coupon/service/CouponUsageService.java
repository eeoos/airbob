package kr.kro.airbob.domain.coupon.service;

import java.util.Collection;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyUsedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotApplicableException;
import kr.kro.airbob.domain.coupon.exception.MemberCouponNotFoundException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import lombok.RequiredArgsConstructor;

/**
 * 발급된 쿠폰의 사용 처리. 예약 생성 트랜잭션 안에서 호출되어 같은 트랜잭션에 참여한다
 * (예약이 실패하면 쿠폰 사용도 함께 롤백).
 */
@Service
@RequiredArgsConstructor
public class CouponUsageService {

	private final MemberCouponRepository memberCouponRepository;
	private final CouponRepository couponRepository;
	private final CouponTimeProvider timeProvider;

	/**
	 * 회원이 보유한 쿠폰을 예약에 사용하고 적용된 할인액을 반환한다.
	 * 미사용 상태일 때만 사용 처리하는 조건부 UPDATE 로 동시 중복 사용을 방지한다.
	 */
	@Transactional
	public long use(Long memberId, Long couponId, Long reservationId, long originalAmount) {
		MemberCoupon memberCoupon = memberCouponRepository.findByMemberIdAndCouponId(
			memberId, couponId)
			.orElseThrow(MemberCouponNotFoundException::new);
		// Checkout uses a shared policy lock: concurrent checkouts remain parallel while
		// admin policy changes wait until this transaction has validated and consumed it.
		Coupon coupon = couponRepository.findByIdForShare(couponId)
			.orElseThrow(CouponNotApplicableException::new);
		var now = timeProvider.now();
		long discount = calculateApplicableDiscount(coupon, originalAmount, now);

		int updated = memberCouponRepository.markUsed(memberCoupon.getId(), reservationId, now);
		if (updated == 0) {
			throw new CouponAlreadyUsedException();
		}

		return discount;
	}

	/**
	 * Calculates the currently applicable discount without consuming or locking inventory.
	 * Checkout always repeats the authoritative validation through {@link #use}.
	 */
	@Transactional(readOnly = true)
	public long preview(Long memberId, Long couponId, long originalAmount) {
		MemberCoupon memberCoupon = memberCouponRepository.findByMemberIdAndCouponIdWithCoupon(
			memberId, couponId)
			.orElseThrow(MemberCouponNotFoundException::new);
		if (memberCoupon.isUsed()) {
			throw new CouponAlreadyUsedException();
		}
		return calculateApplicableDiscount(memberCoupon.getCoupon(), originalAmount, timeProvider.now());
	}

	/**
	 * 예약 취소 시 해당 예약에 사용된 쿠폰을 회원에게 되돌린다(멱등).
	 */
	@Transactional
	public void restore(Long reservationId) {
		memberCouponRepository.restoreByReservationId(reservationId);
	}

	@Transactional
	public void restoreAll(Collection<Long> reservationIds) {
		if (reservationIds.isEmpty()) {
			return;
		}
		memberCouponRepository.restoreAllByReservationIds(reservationIds);
	}

	/**
	 * 취소 보상(취소 실패)으로 예약이 되살아날 때, 복원했던 쿠폰을 다시 사용 처리한다(멱등).
	 */
	@Transactional
	public void reuse(Long reservationId) {
		memberCouponRepository.reuseByReservationId(reservationId, timeProvider.now());
	}

	private long calculateApplicableDiscount(
		Coupon coupon,
		long originalAmount,
		java.time.LocalDateTime now
	) {
		if (!coupon.isUsable(now)) {
			throw new CouponNotApplicableException();
		}
		long discount = coupon.calculateDiscount(originalAmount);
		if (discount <= 0) {
			throw new CouponNotApplicableException();
		}
		return discount;
	}
}
