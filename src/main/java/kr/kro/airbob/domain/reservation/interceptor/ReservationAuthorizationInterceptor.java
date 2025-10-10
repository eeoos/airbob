package kr.kro.airbob.domain.reservation.interceptor;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReservationAuthorizationInterceptor implements HandlerInterceptor {

	private final ReservationRepository reservationRepository;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws
		Exception {
		Map<String, String> pathVariables = (Map<String, String>)request.getAttribute(
			HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

		if (pathVariables == null || !pathVariables.containsKey("reservationUid")) {
			// /api/v1/reservations (POST) 생성 요청 통과
			return true;
		}

		String reservationUidStr = pathVariables.get("reservationUid");
		Long currentMemberId = UserContext.get().id();

		UUID reservationUid;
		try {
			reservationUid = UUID.fromString(reservationUidStr);
		} catch (IllegalArgumentException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "유효하지 않은 예약 ID 형식입니다.");
			return false;
		}

		Long guestId = reservationRepository.findGuestIdByReservationUid(reservationUid).orElse(null);

		if (guestId == null || !guestId.equals(currentMemberId)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "해당 예약에 대한 접근 권한이 없습니다.");
			return false;
		}

		return true;
	}
}
