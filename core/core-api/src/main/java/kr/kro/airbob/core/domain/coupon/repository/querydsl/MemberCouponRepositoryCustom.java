package kr.kro.airbob.core.domain.coupon.repository.querydsl;

import java.time.LocalDateTime;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.core.domain.coupon.MemberCouponProjection;

public interface MemberCouponRepositoryCustom {

	Slice<MemberCouponProjection> findByMemberIdWithCursor(
		Long memberId,
		Long lastId,
		LocalDateTime lastCreatedAt,
		Pageable pageable
	);
}
