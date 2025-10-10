package kr.kro.airbob.domain.accommodation.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
@RequiredArgsConstructor
public class AccommodationAuthorizationInterceptor implements HandlerInterceptor {

    private final AccommodationRepository accommodationRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws IOException {

        String method = request.getMethod();

        if (!(method.equalsIgnoreCase("PATCH") || method.equalsIgnoreCase("DELETE"))) {
            return true; // 수정/삭제가 아니면 통과
        }

        Map<String, String> pathVariables = (Map<String, String>)request.getAttribute(
            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Long accommodationId;
        try {
            if (pathVariables == null || !pathVariables.containsKey("accommodationId")) {
                return true;
            }
            accommodationId = Long.parseLong(pathVariables.get("accommodationId"));
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "유효하지 않은 숙소 ID입니다.");
            return false;
        }

        Long currentMemberId = UserContext.get().id();

        Long hostId = accommodationRepository.findHostIdByAccommodationId(accommodationId)
            .orElse(null);

        if (hostId == null || !hostId.equals(currentMemberId)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "해당 숙소에 대한 수정/삭제 권한이 없습니다.");
            return false;
        }

        return true;
    }
}
