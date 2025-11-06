package kr.kro.airbob.domain.auth.filter;

import java.io.IOException;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
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
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class SessionAuthFilter extends OncePerRequestFilter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final Pattern ACCOMMODATION_DETAIL_PATH_PATTERN = Pattern.compile("^/api/v1/accommodations/\\d+$");
    private static final Pattern REVIEW_PATH_PATTERN = Pattern.compile("^/api/v1/accommodations/\\d+/reviews$");
    private static final Pattern REVIEW_SUMMARY_PATH_PATTERN = Pattern.compile("^/api/v1/accommodations/\\d+/reviews/summary$");
    private static final String SEARCH_PATH = "/api/v1/search/accommodations";

    public SessionAuthFilter(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        // 공개 GET 경로인지 확인
        final boolean isPublicGet = "GET".equals(method) &&
            (ACCOMMODATION_DETAIL_PATH_PATTERN.matcher(path).matches() ||
                REVIEW_PATH_PATTERN.matcher(path).matches() ||
                REVIEW_SUMMARY_PATH_PATTERN.matcher(path).matches() ||
                path.equals(SEARCH_PATH));

        String sessionId = SessionUtil.getSessionIdByCookie(request);
        Long memberId = null;

        // 세션이 있으면 사용자 ID 조회 (없어도 에러 X)
        if (sessionId != null && Boolean.TRUE.equals(redisTemplate.hasKey("SESSION:" + sessionId))) {
            try {
                memberId = checkMemberIdType(sessionId);
            } catch (Exception e) {
                log.warn("[SessionAuthFilter] 유효하지 않은 세션값 (무시): SESSION:{}", sessionId, e);
                // 세션값이 이상해도 공개 GET은 통과시켜야 하므로 예외를 던지지 않음
            }
        }

        // 필수 인증 검사
        // 공개 GET 경로가 아닌데
        // memberId가 null이면 401 에러 반환
        if (!isPublicGet && memberId == null) {
            log.warn("[SessionAuthFilter] 필수 인증 실패 (401): {} {}", method, path);
            sendUnauthorizedError(response);
            return;
        }

        // UserContext 설정 및 필터 체인
        try {
            if (memberId != null) {
                UserContext.set(new UserInfo(memberId));
                log.info("[SessionAuthFilter] 인증된 요청 (User: {}): {} {}", memberId, method, path);
            } else {
                // 비로그인 사용자 (공개 GET): UserContext 설정 X(null 유지)
                log.info("[SessionAuthFilter] 비인증 요청 (Public): {} {}", method, path);
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear(); // ThreadLocal 정리
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
