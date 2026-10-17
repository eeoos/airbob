package kr.kro.airbob.domain.coupon.repository.querydsl;

import java.time.LocalDateTime;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignProjection;

public interface CouponRepositoryCustom {

	Slice<CouponCampaignProjection> findCampaignsWithCursor(
		LocalDateTime now,
		Long lastId,
		LocalDateTime lastIssueStartAt,
		Pageable pageable
	);
}
