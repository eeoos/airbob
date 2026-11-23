package kr.kro.airbob.domain.coupon.service;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.member.MemberReader;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.exception.CouponNotIssuableException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.exception.CouponStockNotPreparedException;
import kr.kro.airbob.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 발급 시도 한 번의 데이터 작업. 트랜잭션은 {@link CouponSkipLockedIssueService}가 시도마다 새로 연다
 * (REPEATABLE_READ, 1초 제한).
 */
@Component
@RequiredArgsConstructor
public class CouponIssuer {

	private final CouponRepository coupons;
	private final CouponIssueUnitStore units;
	private final MemberCouponRepository memberCoupons;
	private final MemberReader members;

	/**
	 * 정책을 FOR SHARE NOWAIT로 읽고, SKIP LOCKED로 잡은 재고 한 개를 지우며 회원 쿠폰을 저장한다.
	 * 잡을 재고가 없으면 {@link NoAvailableUnitException}을 던져 시도 전체를 롤백한다.
	 */
	public void issue(Long couponId, Long memberId) {
		// OSIV can retain first-level entities even across newly started transactions.
		// The locked policy read must reflect this attempt's database state.
		units.clearPersistenceContext();
		Coupon coupon = coupons.findByIdForShareNowait(couponId).orElseThrow(CouponNotFoundException::new);
		validate(coupon);
		if (memberCoupons.existsByMemberIdAndCouponId(memberId, couponId)) throw new CouponAlreadyIssuedException();
		var unit = units.lockAvailable(couponId).orElseThrow(NoAvailableUnitException::new);
		units.consume(couponId, unit);
		memberCoupons.saveAndFlush(MemberCoupon.issue(members.readReference(memberId), coupon));
	}

	/**
	 * 롤백된 시도 뒤 새 스냅샷에서 이미 발급, 발급 불가, 매진을 가려 던진다.
	 * 아무것도 던지지 않으면 재시도할 수 있는 경합이다.
	 */
	public void classifyFailedAttempt(Long couponId, Long memberId) {
		units.clearPersistenceContext();
		if (memberCoupons.existsByMemberIdAndCouponId(memberId, couponId)) throw new CouponAlreadyIssuedException();
		Coupon coupon = coupons.findById(couponId).orElseThrow(CouponNotFoundException::new);
		validate(coupon);
		if (!units.hasRemaining(couponId)) throw new CouponSoldOutException();
	}

	private void validate(Coupon coupon) {
		if (coupon.getTotalQuantity() == null || coupon.getTotalQuantity() < 1
			|| !Boolean.TRUE.equals(coupon.getIsActive()) || !coupon.isIssueOpen(units.databaseNow())) {
			throw new CouponNotIssuableException();
		}
		if (!coupon.isStockPrepared()) throw new CouponStockNotPreparedException();
	}

	/** SKIP LOCKED로 잡을 재고가 없다. 매진인지 경합인지는 {@link #classifyFailedAttempt}가 가린다. */
	static final class NoAvailableUnitException extends RuntimeException { }
}
