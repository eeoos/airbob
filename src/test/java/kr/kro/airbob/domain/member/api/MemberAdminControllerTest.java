package kr.kro.airbob.domain.member.api;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.common.exception.AdminAccessDeniedException;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.dto.MemberAdminRequest;
import kr.kro.airbob.domain.member.dto.MemberAdminResponse;
import kr.kro.airbob.domain.member.exception.MemberRoleChangeNotAllowedException;
import kr.kro.airbob.domain.member.service.MemberAdminService;

@ExtendWith(MockitoExtension.class)
@DisplayName("회원 관리자 API 테스트")
class MemberAdminControllerTest {

	@Mock
	private MemberAdminService memberAdminService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mockMvc = MockMvcBuilders.standaloneSetup(new MemberAdminController(memberAdminService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new FixedCurrentMemberIdArgumentResolver())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.build();
	}

	@Test
	@DisplayName("현재 관리자가 회원 역할을 변경하고 200을 반환한다")
	void changeRole() throws Exception {
		MemberAdminRequest.ChangeRole request = new MemberAdminRequest.ChangeRole(
			MemberRole.ADMIN, "운영 관리자 지정");
		given(memberAdminService.changeRole(1L, 10L, request))
			.willReturn(new MemberAdminResponse.RoleChanged(10L, MemberRole.ADMIN));

		mockMvc.perform(patch("/api/v1/admin/members/10/role")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"role":"ADMIN","reason":"운영 관리자 지정"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.member_id").value(10))
			.andExpect(jsonPath("$.data.role").value("ADMIN"));

		then(memberAdminService).should().changeRole(1L, 10L, request);
	}

	@Test
	@DisplayName("관리자 권한이 없으면 M006 403을 반환한다")
	void rejectNonAdminActor() throws Exception {
		MemberAdminRequest.ChangeRole request = new MemberAdminRequest.ChangeRole(
			MemberRole.ADMIN, "운영 관리자 지정");
		given(memberAdminService.changeRole(1L, 10L, request))
			.willThrow(new AdminAccessDeniedException());

		mockMvc.perform(patch("/api/v1/admin/members/10/role")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"role":"ADMIN","reason":"운영 관리자 지정"}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("M006"));
	}

	@Test
	@DisplayName("역할이 누락되면 C001 400을 반환한다")
	void rejectMissingRole() throws Exception {
		assertInvalidRequest("""
			{"reason":"운영 관리자 지정"}
			""");
	}

	@Test
	@DisplayName("알 수 없는 역할이면 C001 400을 반환한다")
	void rejectUnknownRole() throws Exception {
		assertInvalidRequest("""
			{"role":"SUPER_ADMIN","reason":"운영 관리자 지정"}
			""");
	}

	@Test
	@DisplayName("변경 사유가 공백이면 C001 400을 반환한다")
	void rejectBlankReason() throws Exception {
		assertInvalidRequest("""
			{"role":"ADMIN","reason":"   "}
			""");
	}

	@Test
	@DisplayName("변경 사유가 255자를 초과하면 C001 400을 반환한다")
	void rejectTooLongReason() throws Exception {
		String reason = "a".repeat(256);

		assertInvalidRequest("""
			{"role":"ADMIN","reason":"%s"}
			""".formatted(reason));
	}

	@Test
	@DisplayName("자신의 관리자 권한 회수를 거부하면 M007 409를 반환한다")
	void rejectSelfDemotion() throws Exception {
		MemberAdminRequest.ChangeRole request = new MemberAdminRequest.ChangeRole(
			MemberRole.MEMBER, "관리자 권한 회수");
		given(memberAdminService.changeRole(1L, 10L, request))
			.willThrow(new MemberRoleChangeNotAllowedException());

		mockMvc.perform(patch("/api/v1/admin/members/10/role")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"role":"MEMBER","reason":"관리자 권한 회수"}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("M007"));
	}

	private void assertInvalidRequest(String content) throws Exception {
		mockMvc.perform(patch("/api/v1/admin/members/10/role")
				.contentType(MediaType.APPLICATION_JSON)
				.content(content))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C001"));

		then(memberAdminService).shouldHaveNoInteractions();
	}

	private static class FixedCurrentMemberIdArgumentResolver implements HandlerMethodArgumentResolver {

		@Override
		public boolean supportsParameter(MethodParameter parameter) {
			return parameter.hasParameterAnnotation(CurrentMemberId.class)
				&& Long.class.equals(parameter.getParameterType());
		}

		@Override
		public Object resolveArgument(
			MethodParameter parameter,
			ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest,
			WebDataBinderFactory binderFactory
		) {
			return 1L;
		}
	}
}
