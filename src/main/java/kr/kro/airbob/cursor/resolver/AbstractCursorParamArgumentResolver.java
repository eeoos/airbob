package kr.kro.airbob.cursor.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorPayload;
import kr.kro.airbob.cursor.exception.CursorPageSizeException;
import kr.kro.airbob.cursor.util.CursorDecoder;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractCursorParamArgumentResolver<C extends CursorPayload, R>
	implements HandlerMethodArgumentResolver {

	protected final CursorDecoder cursorDecoder;

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CursorParam.class)
			&& parameter.getParameterType().equals(getSupportedRequestType());
	}

	@Override
	public Object resolveArgument(
		MethodParameter parameter,
		ModelAndViewContainer mavContainer,
		NativeWebRequest webRequest,
		WebDataBinderFactory binderFactory
	) {
		CursorParam annotation =
			parameter.getParameterAnnotation(CursorParam.class);

		int size = parseSize(webRequest, annotation);
		C cursorData = parseCursorData(webRequest, annotation);

		return createRequest(size, cursorData);
	}

	private C parseCursorData(
		NativeWebRequest webRequest,
		CursorParam annotation
	) {
		String cursorParam =
			webRequest.getParameter(annotation.cursorParam());

		return cursorDecoder.decode(
			cursorParam,
			getCursorDataType()
		);
	}

	private int parseSize(
		NativeWebRequest webRequest,
		CursorParam annotation
	) {
		String sizeParam =
			webRequest.getParameter(annotation.sizeParam());

		int size;
		try {
			size = sizeParam != null
				? Integer.parseInt(sizeParam)
				: annotation.defaultSize();
		} catch (NumberFormatException exception) {
			throw new CursorPageSizeException();
		}

		if (size < 1 || size > annotation.maxSize()) {
			throw new CursorPageSizeException();
		}

		return size;
	}

	protected abstract Class<R> getSupportedRequestType();

	protected abstract Class<C> getCursorDataType();

	protected abstract R createRequest(int size, C cursorData);
}
