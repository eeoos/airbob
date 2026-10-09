package kr.kro.airbob.domain.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.exception.CouponAlreadyIssuedException;
import kr.kro.airbob.domain.coupon.exception.CouponNotFoundException;
import kr.kro.airbob.domain.coupon.exception.CouponNotIssuableException;
import kr.kro.airbob.domain.coupon.exception.CouponSoldOutException;
import kr.kro.airbob.domain.coupon.exception.CouponStockNotPreparedException;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 발급의 DB 트랜잭션 경계.
 * DB 조건부 UPDATE 발급과 Redis Lua 승인 후 영속화를 담당한다.
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
	@Transactional
	public void issueWithConditionalUpdate(Long couponId, Long memberId) {
		LocalDateTime now = timeProvider.now();
		int updated = couponRepository.incrementIssuedQuantityIfIssuable(couponId, now);
		Coupon coupon = couponRepository.findById(couponId)
			.orElseThrow(CouponNotFoundException::new);

		if (coupon.isRedisStockPrepared()
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

	/**
	 * Redis Lua가 이미 재고/중복을 통제한 뒤 호출되는 영속화 전용 경로.
	 * 재고 검사 없이 DB issuedQuantity 를 원자적 UPDATE 로 누적하고 발급분을 기록
	 */
	@Transactional
	public void persistApprovedIssue(Long couponId, Long memberId) {
		Coupon coupon = couponRepository.findById(couponId)
			.orElseThrow(CouponNotFoundException::new);

		if (!coupon.isRedisStockPrepared()) {
			throw new CouponStockNotPreparedException();
		}
		couponRepository.incrementIssuedQuantity(couponId);
		memberCouponRepository.save(MemberCoupon.issue(memberRepository.getReferenceById(memberId), coupon));
	}
}
