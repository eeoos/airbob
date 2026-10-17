package kr.kro.airbob.cursor.resolver;

import org.springframework.stereotype.Component;

import kr.kro.airbob.cursor.dto.CouponCampaignCursorData;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.util.CursorDecoder;

@Component
public class CouponCampaignCursorParamArgumentResolver
	extends AbstractCursorParamArgumentResolver<
		CouponCampaignCursorData,
		CursorRequest.CouponCampaignCursorPageRequest
	> {

	public CouponCampaignCursorParamArgumentResolver(CursorDecoder cursorDecoder) {
		super(cursorDecoder);
	}

	@Override
	protected Class<CursorRequest.CouponCampaignCursorPageRequest> getSupportedRequestType() {
		return CursorRequest.CouponCampaignCursorPageRequest.class;
	}

	@Override
	protected Class<CouponCampaignCursorData> getCursorDataType() {
		return CouponCampaignCursorData.class;
	}

	@Override
	protected CursorRequest.CouponCampaignCursorPageRequest createRequest(
		int size,
		CouponCampaignCursorData cursorData
	) {
		return CursorRequest.CouponCampaignCursorPageRequest.builder()
			.size(size)
			.lastId(cursorData != null ? cursorData.id() : null)
			.lastIssueStartAt(
				cursorData != null
					? cursorData.lastIssueStartAt()
					: null
			)
			.build();
	}
}
