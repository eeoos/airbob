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
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 발급의 DB 트랜잭션 경계. 락/카운터 같은 동시성 제어는 호출자({@code CouponIssueService})가
 * 트랜잭션 바깥에서 담당한다(예약 도메인의 ReservationTransactionService 와 동일한 분리).
 */
@Service
@RequiredArgsConstructor
public class CouponIssueTransactionService {

	private final CouponRepository couponRepository;
	private final MemberCouponRepository memberCouponRepository;
	private final MemberRepository memberRepository;

	/**
	 * 재고를 DB issuedQuantity 로 검사/차감하는 발급. 락이 없으면 read-modify-write 경합으로
	 * 초과 발급(lost update)이 발생한다 — 무락(anti-pattern)/분산락 경로가 공유한다.
	 */
	@Transactional
	public void issue(Long couponId, Long memberId) {
		Coupon coupon = couponRepository.findById(couponId)
			.orElseThrow(CouponNotFoundException::new);

		if (coupon.isSoldOut()) {
			throw new CouponSoldOutException();
		}
		if (!coupon.isIssuable(LocalDateTime.now())) {
			throw new CouponNotIssuableException();
		}
		if (memberCouponRepository.existsByMemberIdAndCouponId(memberId, couponId)) {
			throw new CouponAlreadyIssuedException();
		}

		// 발급 수를 원자적 UPDATE 로 먼저 증가(X-lock) → 이후 발급분 INSERT(FK S-lock) 순서로
		// 락 획득 순서를 X→S 로 고정해 동시 발급 시 데드락을 피한다.
		// 단, 위의 sold-out 검사는 findById 스냅샷 기준이라 동시성 제어가 없으면 초과 발급이 발생한다.
		couponRepository.incrementIssuedQuantity(couponId);
		memberCouponRepository.save(MemberCoupon.issue(memberRepository.getReferenceById(memberId), coupon));
	}

	/**
	 * Redis 원자적 카운터가 이미 재고/중복을 통제한 뒤 호출되는 영속화 전용 경로.
	 * 재고 검사 없이 DB issuedQuantity 를 원자적 UPDATE 로 누적하고 발급분을 기록한다.
	 */
	@Transactional
	public void persistIssued(Long couponId, Long memberId) {
		Coupon coupon = couponRepository.findById(couponId)
			.orElseThrow(CouponNotFoundException::new);

		couponRepository.incrementIssuedQuantity(couponId);
		memberCouponRepository.save(MemberCoupon.issue(memberRepository.getReferenceById(memberId), coupon));
	}
}
