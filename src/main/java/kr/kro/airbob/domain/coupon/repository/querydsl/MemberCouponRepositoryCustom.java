package kr.kro.airbob.domain.coupon.repository.querydsl;

import java.time.LocalDateTime;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.coupon.repository.projection.MemberCouponProjection;

public interface MemberCouponRepositoryCustom {

	Slice<MemberCouponProjection> findByMemberIdWithCursor(
		Long memberId,
		Long lastId,
		LocalDateTime lastCreatedAt,
		Pageable pageable
	);
}
