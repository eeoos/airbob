package kr.kro.airbob.cursor.dto;

import java.time.LocalDateTime;

public record CouponCampaignCursorData(
	Long id,
	LocalDateTime lastIssueStartAt
) implements CursorPayload {

	@Override
	public void validate() {
		CursorPayload.super.validate();
		if (lastIssueStartAt == null) {
			throw new IllegalArgumentException("쿠폰 캠페인 커서 경계 값이 유효하지 않습니다.");
		}
	}
}
