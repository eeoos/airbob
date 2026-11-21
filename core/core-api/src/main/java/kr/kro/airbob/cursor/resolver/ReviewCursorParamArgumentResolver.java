package kr.kro.airbob.cursor.resolver;

import org.springframework.stereotype.Component;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.ReviewCursorData;
import kr.kro.airbob.cursor.util.CursorDecoder;

@Component
public class ReviewCursorParamArgumentResolver
	extends AbstractCursorParamArgumentResolver<
		ReviewCursorData,
		CursorRequest.ReviewCursorPageRequest
	> {

	public ReviewCursorParamArgumentResolver(CursorDecoder cursorDecoder) {
		super(cursorDecoder);
	}

	@Override
	protected Class<CursorRequest.ReviewCursorPageRequest> getSupportedRequestType() {
		return CursorRequest.ReviewCursorPageRequest.class;
	}

	@Override
	protected Class<ReviewCursorData> getCursorDataType() {
		return ReviewCursorData.class;
	}

	@Override
	protected CursorRequest.ReviewCursorPageRequest createRequest(
		int size,
		ReviewCursorData cursorData
	) {
		return CursorRequest.ReviewCursorPageRequest.builder()
			.size(size)
			.lastId(cursorData != null ? cursorData.id() : null)
			.lastCreatedAt(
				cursorData != null
					? cursorData.lastCreatedAt()
					: null
			)
			.lastRating(
				cursorData != null
					? cursorData.lastRating()
					: null
			)
			.build();
	}
}
