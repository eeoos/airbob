package kr.kro.airbob.domain.auth.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.auth.exception.AuthenticationRequiredException;

@DisplayName("현재 회원 ID argument resolver 테스트")
class CurrentMemberIdArgumentResolverTest {

	private final CurrentMemberIdArgumentResolver resolver =
		new CurrentMemberIdArgumentResolver();

	@AfterEach
	void clearUserContext() {
		UserContext.clear();
	}

	@Test
	@DisplayName("인증된 회원 ID를 반환한다")
	void resolvesAuthenticatedMemberId() throws Exception {
		UserContext.set(new UserInfo(7L));

		Object resolved = resolver.resolveArgument(parameter("required", Long.class), null, null, null);

		assertThat(resolved).isEqualTo(7L);
	}

	@Test
	@DisplayName("필수 인증 파라미터인데 회원 정보가 없으면 401 예외를 던진다")
	void rejectsMissingRequiredMember() throws Exception {
		assertThatThrownBy(() -> resolver.resolveArgument(
			parameter("required", Long.class), null, null, null))
			.isInstanceOfSatisfying(AuthenticationRequiredException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.UNAUTHORIZED_ACCESS));
	}

	@Test
	@DisplayName("선택 인증 파라미터인데 회원 정보가 없으면 null을 반환한다")
	void resolvesMissingOptionalMemberAsNull() throws Exception {
		Object resolved = resolver.resolveArgument(parameter("optional", Long.class), null, null, null);

		assertThat(resolved).isNull();
	}

	@Test
	@DisplayName("Long이 아닌 파라미터에는 사용할 수 없다")
	void rejectsUnsupportedParameterType() throws Exception {
		assertThatThrownBy(() -> resolver.resolveArgument(
			parameter("invalidType", String.class), null, null, null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("@CurrentMemberId는 Long 타입만 사용할 수 있습니다.");
	}

	private MethodParameter parameter(String methodName, Class<?> parameterType) throws Exception {
		Method method = HandlerFixture.class.getDeclaredMethod(methodName, parameterType);
		return new MethodParameter(method, 0);
	}

	private static class HandlerFixture {
		@SuppressWarnings("unused")
		void required(@CurrentMemberId Long memberId) {
		}

		@SuppressWarnings("unused")
		void optional(@CurrentMemberId(required = false) Long memberId) {
		}

		@SuppressWarnings("unused")
		void invalidType(@CurrentMemberId String memberId) {
		}
	}
}
