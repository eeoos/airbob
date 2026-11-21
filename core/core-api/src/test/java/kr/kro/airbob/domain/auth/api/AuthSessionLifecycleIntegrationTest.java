package kr.kro.airbob.domain.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import jakarta.servlet.http.Cookie;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.config.RedisConfig;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.repository.SessionRedisRepository;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.auth.service.AuthService;
import kr.kro.airbob.domain.auth.service.SessionIssuanceService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberHistoryRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.repository.projection.MemberProfileProjection;
import kr.kro.airbob.domain.member.service.MemberService;

// Exercise real MVC, authentication, session storage and Redis serialization; only MySQL repositories are mocked.
@Testcontainers
class AuthSessionLifecycleIntegrationTest {
    private static final String EMAIL = "docs@example.invalid";
    private static final String PASSWORD = "example-password";
    private static final String PASSWORD_HASH = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
        .withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static RedisTemplate<String, Object> redis;
    private final ObjectMapper mapper = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private MockMvc mvc;

    @BeforeAll
    static void connectToIsolatedRedis() {
        connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        redis = new RedisConfig().redisTemplate(connections);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedisConnections() {
        connections.destroy();
    }

    @BeforeEach
    void setUp() {
        UserContext.clear();
        try (var connection = connections.getConnection()) {
            connection.serverCommands().flushDb();
        }
        MemberRepository members = mock(MemberRepository.class);
        Member member = Member.builder().id(10L).email(EMAIL).password(PASSWORD_HASH)
            .nickname("여행자").status(MemberStatus.ACTIVE).build();
        when(members.findByEmailAndStatus(EMAIL, MemberStatus.ACTIVE)).thenReturn(Optional.of(member));
        when(members.findByIdAndStatusForUpdate(10L, MemberStatus.ACTIVE)).thenReturn(Optional.of(member));
        when(members.findProfileByIdAndStatus(10L, MemberStatus.ACTIVE))
            .thenReturn(Optional.of(new MemberProfileProjection(10L, EMAIL, "여행자")));

        SessionRedisRepository sessions = new SessionRedisRepository(redis);
        AuthService authentication = new AuthService(members, sessions, new SessionIssuanceService(members, sessions));
        MemberService profiles = new MemberService(members, mock(MemberHistoryRepository.class), sessions, Clock.systemUTC());
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(authentication, profiles))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .addFilter(new SessionAuthFilter(redis, mapper), "/api/v1/*")
            .build();
    }

    @Test
    void loginCookieAuthenticatesUntilLogoutRevokesTheRedisSession() throws Exception {
        Cookie session = login();
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.getPath()).isEqualTo("/");
        assertThat(session.getMaxAge()).isEqualTo(3600);
        assertThat(redis.getExpire("SESSION:" + session.getValue())).isBetween(3590L, 3600L);
        assertThat(redis.hasKey("MEMBER_SESSION_ACTIVE:10")).isTrue();

        mvc.perform(get("/api/v1/auth/me").cookie(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(10))
            .andExpect(jsonPath("$.data.email").value(EMAIL))
            .andExpect(jsonPath("$.data.nickname").value("여행자"));

        MockHttpServletResponse logout = mvc.perform(post("/api/v1/auth/logout").cookie(session))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(mapper.readTree(logout.getContentAsString())).isEqualTo(mapper.readTree("{\"success\":true}"));
        assertThat(logout.getHeader("Set-Cookie")).isNull();
        assertThat(redis.hasKey("SESSION:" + session.getValue())).isFalse();
        assertThat(redis.opsForZSet().score("MEMBER_SESSIONS:10", session.getValue())).isNull();

        mvc.perform(get("/api/v1/auth/me").cookie(session))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("M004"));
        mvc.perform(post("/api/v1/auth/logout").cookie(session))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("M004"));
    }

    @Test
    void logoutRevokesOnlyThePresentedSession() throws Exception {
        Cookie first = login();
        Cookie second = login();
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        mvc.perform(post("/api/v1/auth/logout").cookie(first)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(second)).andExpect(status().isOk());
    }

    @Test
    void rejectedCredentialsDoNotIssueCookiesOrRedisSessions() throws Exception {
        MockHttpServletResponse wrongPassword = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", EMAIL, "password", "wrong-password"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("M003"))
            .andReturn().getResponse();
        MockHttpServletResponse missingMember = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", "missing@example.invalid", "password", PASSWORD))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("M001"))
            .andReturn().getResponse();

        assertThat(wrongPassword.getCookie("SESSION_ID")).isNull();
        assertThat(missingMember.getCookie("SESSION_ID")).isNull();
        assertThat(redis.hasKey("MEMBER_SESSION_ACTIVE:10")).isFalse();
        assertThat(redis.opsForZSet().size("MEMBER_SESSIONS:10")).isZero();
    }

    private Cookie login() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(mapper.readTree(response.getContentAsString())).isEqualTo(mapper.readTree("{\"success\":true}"));
        assertThat(response.getCookie("SESSION_ID")).isNotNull();
        return response.getCookie("SESSION_ID");
    }
}
