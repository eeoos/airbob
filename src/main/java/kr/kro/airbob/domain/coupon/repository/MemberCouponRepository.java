package kr.kro.airbob.domain.coupon.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.domain.coupon.entity.MemberCoupon;

@Repository
public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {

	boolean existsByMemberIdAndCouponId(Long memberId, Long couponId);

	long countByCouponId(Long couponId);

	Optional<MemberCoupon> findByMemberIdAndCouponId(Long memberId, Long couponId);

	/**
	 * 미사용 상태일 때만 사용 처리한다. 영향 행이 0이면 이미 사용된 것 → 중복 사용 방지.
	 */
	@Modifying
	@Query("update MemberCoupon mc set mc.used = true, mc.usedAt = :usedAt, mc.reservationId = :reservationId "
		+ "where mc.id = :id and mc.used = false")
	int markUsed(@Param("id") Long id, @Param("reservationId") Long reservationId,
		@Param("usedAt") LocalDateTime usedAt);

	/**
	 * 예약 취소 시 해당 예약에 사용된 쿠폰을 복원한다(used=false). 멱등 — 복원할 게 없으면 0.
	 * 취소 보상(재사용)에서 다시 찾을 수 있도록 reservation_id 링크는 유지한다.
	 */
	@Modifying
	@Query("update MemberCoupon mc set mc.used = false, mc.usedAt = null "
		+ "where mc.reservationId = :reservationId and mc.used = true")
	int restoreByReservationId(@Param("reservationId") Long reservationId);

	/**
	 * 취소 보상(취소 실패) 시 복원했던 쿠폰을 다시 사용 처리한다. 멱등 — 대상이 없으면 0.
	 */
	@Modifying
	@Query("update MemberCoupon mc set mc.used = true, mc.usedAt = :usedAt "
		+ "where mc.reservationId = :reservationId and mc.used = false")
	int reuseByReservationId(@Param("reservationId") Long reservationId, @Param("usedAt") LocalDateTime usedAt);
}
