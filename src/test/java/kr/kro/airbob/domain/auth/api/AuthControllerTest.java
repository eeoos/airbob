package kr.kro.airbob.domain.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.auth.service.AuthService;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.member.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private MemberService memberService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new AuthController(authService, memberService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new FixedCurrentMemberIdArgumentResolver())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"email\":null,\"password\":\"example-password\"}",
        "{\"email\":\"\",\"password\":\"example-password\"}",
        "{\"email\":\"   \",\"password\":\"example-password\"}",
        "{\"email\":\"docs@example.invalid\"}",
        "{\"email\":\"docs@example.invalid\",\"password\":null}",
        "{\"email\":\"docs@example.invalid\",\"password\":\"\"}",
        "{\"email\":\"docs@example.invalid\",\"password\":\"   \"}"
    })
    void loginRejectsMissingOrBlankCredentialsBeforeIssuingSession(String body) throws Exception {
        var response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("C001"))
            .andReturn().getResponse();

        assertThat(response.getCookie("SESSION_ID")).isNull();
        then(authService).shouldHaveNoInteractions();
    }

    @Test
    void loginValidationMasksPasswordAndMalformedJsonDoesNotIssueSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"docs@example.invalid\",\"password\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.errors[0].field").value("password"))
            .andExpect(jsonPath("$.error.errors[0].value").value("[REDACTED]"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("C001"));
        then(authService).shouldHaveNoInteractions();
    }

    @Test
    void getMyInfoUsesCurrentMemberAndExposesOnlyViewerFields() throws Exception {
        MemberResponse.MeInfo me = new MemberResponse.MeInfo(
            10L, "guest@airbob.test", "guest");
        given(memberService.getMemberInfo(10L)).willReturn(me);

        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(10))
            .andExpect(jsonPath("$.data.email").value("guest@airbob.test"))
            .andExpect(jsonPath("$.data.nickname").value("guest"))
            .andExpect(jsonPath("$.data.thumbnail_image_url").doesNotExist());

        then(memberService).should().getMemberInfo(10L);
        then(authService).shouldHaveNoInteractions();
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
            return 10L;
        }
    }
}
