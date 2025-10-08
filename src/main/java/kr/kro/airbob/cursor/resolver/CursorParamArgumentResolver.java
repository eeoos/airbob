package kr.kro.airbob.cursor.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.exception.CursorPageSizeException;
import kr.kro.airbob.cursor.util.CursorDecoder;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CursorParamArgumentResolver implements HandlerMethodArgumentResolver {

	protected final CursorDecoder cursorDecoder;

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CursorParam.class) &&
			parameter.getParameterType().equals(CursorRequest.CursorPageRequest.class);
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
		NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

		CursorParam annotation = parameter.getParameterAnnotation(CursorParam.class);

		int size = parseSize(webRequest, annotation);
		CursorResponse.CursorData cursorData = parseCursorData(webRequest, annotation);

		return CursorRequest.CursorPageRequest.builder()
			.size(size)
			.lastId(cursorData != null ? cursorData.getId() : null)
			.lastCreatedAt(cursorData != null ? cursorData.getLastCreatedAt() : null)
			.build();
	}

	protected CursorResponse.CursorData parseCursorData(NativeWebRequest webRequest, CursorParam annotation) {

		// 커서 디코딩
		String cursorParam = webRequest.getParameter(annotation.cursorParam());
		return cursorDecoder.decode(cursorParam);
	}

	protected int parseSize(NativeWebRequest webRequest, CursorParam annotation){

		// size 처리
		String sizeParam = webRequest.getParameter(annotation.sizeParam());
		int size = sizeParam != null ? Integer.parseInt(sizeParam) : annotation.defaultSize();

		if (size < 1) {
			throw new CursorPageSizeException();
		}

		return size;
	}
}
