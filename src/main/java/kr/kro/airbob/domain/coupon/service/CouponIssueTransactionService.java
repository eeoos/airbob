package kr.kro.airbob.domain.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.exception.CouponNotIssuableException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 발급의 DB 트랜잭션 경계.
 * 조건부 UPDATE 비교군의 트랜잭션 경계. 첫 DB 연산은 기존의 조건부 UPDATE이다.
 */
@Service
@RequiredArgsConstructor
public class CouponIssueTransactionService {

	private final CouponRepository couponRepository;
	private final MemberCouponRepository memberCouponRepository;
	private final MemberRepository memberRepository;
	private final CouponTimeProvider timeProvider;

	/**
	 * 조건부 UPDATE의 행 락을 커밋까지 유지한다. 중복 또는 저장 실패 시 수량 증가도 롤백된다.
	 * UPDATE를 첫 DB 연산으로 실행해 이후 조회가 선행 발급의 커밋을 볼 수 있게 한다.
	 */
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public void issueWithConditionalUpdate(Long couponId, Long memberId) {
		LocalDateTime now = timeProvider.now();
		int updated = couponRepository.incrementIssuedQuantityIfIssuable(couponId, now);
		Coupon coupon = couponRepository.findById(couponId)
			.orElseThrow(CouponNotFoundException::new);

		if (coupon.isStockPrepared()
			|| !Boolean.TRUE.equals(coupon.getIsActive()) || !coupon.isIssueOpen(now)) {
			throw new CouponNotIssuableException();
		}
		if (memberCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
			throw new CouponAlreadyIssuedException();
		}
		if (updated == 0) {
			throw new CouponSoldOutException();
		}

		// UPDATE(X-lock) → INSERT(FK S-lock) 순서를 유지한다.
		memberCouponRepository.save(MemberCoupon.issue(memberRepository.getReferenceById(memberId), coupon));
	}
}
