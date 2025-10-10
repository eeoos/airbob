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
import kr.kro.airbob.domain.auth.common.SessionUtil;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class SessionAuthFilter extends OncePerRequestFilter {

    private final RedisTemplate<String, Object> redisTemplate;

    public SessionAuthFilter(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        log.info("doFilterInternal");
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.equals("/api/accommodations") && method.equals("GET")) {
            filterChain.doFilter(request, response); // 필터 건너뜀
            return;
        }

        String sessionId = SessionUtil.getSessionIdByCookie(request);

        // 세션 ID가 없거나 레디스에 없으면 401 Unauthorized 반환
        if (sessionId == null || !Boolean.TRUE.equals(redisTemplate.hasKey("SESSION:" + sessionId))) {
            log.warn("[SessionAuthFilter] 인증 실패 - 세션 없음 또는 무효: {}", sessionId);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\": \"인증이 필요합니다.\"}");
            return;
        }

        try {
            long memberId = checkMemberIdType(sessionId);

            UserInfo userInfo = new UserInfo(memberId);
            UserContext.set(userInfo);

            filterChain.doFilter(request, response);
        } finally{
            UserContext.clear();
        }
    }

    private long checkMemberIdType(String sessionId) {
        Object value = redisTemplate.opsForValue().get("SESSION:" + sessionId);

        if (value instanceof Number number) {
            return number.longValue();
        } else {
            throw new IllegalStateException("Unexpected session type: " + value.getClass());
        }
    }
}
