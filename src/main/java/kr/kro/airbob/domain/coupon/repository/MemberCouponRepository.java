package kr.kro.airbob.domain.coupon.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.domain.coupon.entity.MemberCoupon;

@Repository
public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {

	boolean existsByMemberIdAndCouponId(Long memberId, Long couponId);

	long countByCouponId(Long couponId);
}
