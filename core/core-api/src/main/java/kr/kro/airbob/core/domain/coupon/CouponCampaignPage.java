package kr.kro.airbob.core.domain.coupon;

import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.core.support.cursor.dto.CursorResponse;

/** 발급 캠페인 목록 한 페이지. 발급 상태는 발급과 같은 MySQL 시각으로 계산한다. */
public record CouponCampaignPage(
	List<Item> campaigns,
	CursorResponse.PageInfo pageInfo) {

	public record Item(CouponCampaignProjection campaign, CouponIssuanceStatus issuanceStatus) {

		public static Item of(CouponCampaignProjection campaign, LocalDateTime now) {
			return new Item(campaign, CouponIssuanceStatus.resolve(
				campaign.issueStartAt(), campaign.totalQuantity(), campaign.hasRemainingStock(), now));
		}
	}
}
