package kr.kro.airbob.domain.coupon.service;

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
 * 쿠폰 발급의 DB 트랜잭션 경계. 분산 락 또는 Lua 동시성 제어는 호출 서비스가
 * 트랜잭션 바깥에서 담당한다.
 */
@Service
@RequiredArgsConstructor
public class CouponIssueTransactionService {

	private final CouponRepository couponRepository;
	private final MemberCouponRepository memberCouponRepository;
	private final MemberRepository memberRepository;
	private final CouponTimeProvider timeProvider;
	private final CouponRedisStockManager stockManager;

	/**
	 * 분산 락 안에서 DB 상태를 검증하고 발급한다.
	 */
	@Transactional
	public void issueUnderLock(Long couponId, Long memberId) {
		Coupon coupon = couponRepository.findByIdForUpdate(couponId)
			.orElseThrow(CouponNotFoundException::new);

		if (coupon.isRedisStockPrepared() || stockManager.isPrepared(couponId)) {
			throw new CouponNotIssuableException();
		}
		if (!Boolean.TRUE.equals(coupon.getIsActive()) || !coupon.isIssueOpen(timeProvider.now())) {
			throw new CouponNotIssuableException();
		}
		if (memberCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
			throw new CouponAlreadyIssuedException();
		}
		if (coupon.isSoldOut()) {
			throw new CouponSoldOutException();
		}

		// 발급 수를 원자적 UPDATE 로 먼저 증가(X-lock) → 이후 발급분 INSERT(FK S-lock) 순서로
		// 락 획득 순서를 X→S 로 고정해 동시 발급 시 데드락을 피한다.
		couponRepository.incrementIssuedQuantity(couponId);
		memberCouponRepository.save(MemberCoupon.issue(memberRepository.getReferenceById(memberId), coupon));
	}

	/**
	 * Redis Lua가 이미 재고/중복을 통제한 뒤 호출되는 영속화 전용 경로.
	 * 재고 검사 없이 DB issuedQuantity 를 원자적 UPDATE 로 누적하고 발급분을 기록한다.
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
