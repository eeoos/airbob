package kr.kro.airbob.cursor.resolver;

import org.springframework.stereotype.Component;

import kr.kro.airbob.cursor.dto.CursorData;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.util.CursorDecoder;

@Component
public class CursorParamArgumentResolver
	extends AbstractCursorParamArgumentResolver<
		CursorData,
		CursorRequest.CursorPageRequest
	> {

	public CursorParamArgumentResolver(CursorDecoder cursorDecoder) {
		super(cursorDecoder);
	}

	@Override
	protected Class<CursorRequest.CursorPageRequest> getSupportedRequestType() {
		return CursorRequest.CursorPageRequest.class;
	}

	@Override
	protected Class<CursorData> getCursorDataType() {
		return CursorData.class;
	}

	@Override
	protected CursorRequest.CursorPageRequest createRequest(
		int size,
		CursorData cursorData
	) {

		return CursorRequest.CursorPageRequest.builder()
			.size(size)
			.lastId(cursorData != null ? cursorData.id() : null)
			.lastCreatedAt(
				cursorData != null
					? cursorData.lastCreatedAt()
					: null
			)
			.build();
	}
}
