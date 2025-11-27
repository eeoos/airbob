package kr.kro.airbob.domain.auth.filter;

import java.io.IOException;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.dto.ErrorResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.auth.common.SessionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class SessionAuthFilter extends OncePerRequestFilter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();


    // 인증이 필요 없는 경로 목록 (메서드 무관)
    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/v1/auth/login",  // 로그인
        "/api/v1/members"      // 회원가입
    );

    // GET 메서드일 때만 인증이 필요 없는 경로 목록
    private static final String[] PUBLIC_GET_PATHS = {
        "/api/v1/accommodations/*",          // 숙소 상세
        "/api/v1/accommodations/*/reviews",  // 리뷰 목록
        "/api/v1/accommodations/*/reviews/summary", // 리뷰 요약
        "/api/v1/search/accommodations",     // 검색
        // "/api/v1/search/recommendations"     // 인기 여행지 추천
    };

    public SessionAuthFilter(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        // HTTP 메서드와 무관하게 항상 공개되는 경로
        boolean isPublicPath = PUBLIC_PATHS.stream()
            .anyMatch(p -> pathMatcher.match(p, path));

        // GET 메서드일 때만 공개되는 경로
        boolean isPublicGet = "GET".equals(method) &&
            Arrays.stream(PUBLIC_GET_PATHS)
                .anyMatch(p -> pathMatcher.match(p, path));

        String sessionId = SessionUtil.getSessionIdByCookie(request);
        Long memberId = null;

        if (sessionId != null && Boolean.TRUE.equals(redisTemplate.hasKey("SESSION:" + sessionId))) {
            try {
                memberId = checkMemberIdType(sessionId);
            } catch (Exception e) {
                log.warn("[SessionAuthFilter] 유효하지 않은 세션값 (무시): SESSION:{}", sessionId, e);
            }
        }

        // 인증 검사 로직
        // 공개 경로도 아니고, 공개 GET 경로도 아닌데 memberId(인증)가 없으면 401
        if (!isPublicPath && !isPublicGet && memberId == null) {
            log.warn("[SessionAuthFilter] 필수 인증 실패 (401): {} {}", method, path);
            sendUnauthorizedError(response);
            return;
        }

        try {
            if (memberId != null) {
                UserContext.set(new UserInfo(memberId));
                log.info("[SessionAuthFilter] 인증된 요청 (User: {}): {} {}", memberId, method, path);
            } else {
                log.info("[SessionAuthFilter] 비인증 요청 (Public): {} {}", method, path);
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private void sendUnauthorizedError(HttpServletResponse response) throws IOException {
        ErrorResponse errorResponse = ErrorResponse.of(ErrorCode.UNAUTHORIZED_ACCESS);
        ApiResponse<?> apiResponse = ApiResponse.error(errorResponse);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");

        String jsonResponse = objectMapper.writeValueAsString(apiResponse);
        response.getWriter().write(jsonResponse);
    }

    private long checkMemberIdType(String sessionId) {
        Object value = redisTemplate.opsForValue().get("SESSION:" + sessionId);

        if (value instanceof Number number) {
            return number.longValue();
        } else if (value != null) {
            log.error("세션 값 타입 오류: SESSION:{} 값 = {}, 타입 = {}", sessionId, value, value.getClass().getName());
            throw new IllegalStateException("Unexpected session type: " + value.getClass());
        } else {
            log.error("세션 값 누락: SESSION:{}", sessionId);
            throw new IllegalStateException("Session value not found in Redis for key: SESSION:" + sessionId);
        }
    }
}
