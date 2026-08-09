package kr.kro.airbob.domain.auth.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.auth.exception.AuthenticationRequiredException;

@Component
public class CurrentMemberIdArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentMemberId.class);
	}

	@Override
	public Object resolveArgument(
		MethodParameter parameter,
		ModelAndViewContainer mavContainer,
		NativeWebRequest webRequest,
		WebDataBinderFactory binderFactory) {

		if (!Long.class.equals(parameter.getParameterType())) {
			throw new IllegalStateException(
				"@CurrentMemberId는 Long 타입만 사용할 수 있습니다."
			);
		}

		CurrentMemberId annotation = parameter.getParameterAnnotation(CurrentMemberId.class);

		UserInfo userInfo = UserContext.get();

		if (userInfo != null && userInfo.id() != null) {
			return userInfo.id();
		}

		if (annotation != null && annotation.required()) {
			throw new AuthenticationRequiredException();
		}

		return null;
	}
}
