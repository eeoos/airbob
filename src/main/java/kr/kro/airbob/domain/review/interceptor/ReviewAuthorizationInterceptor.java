package kr.kro.airbob.domain.review.interceptor;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReviewAuthorizationInterceptor implements HandlerInterceptor {

	private final ReviewRepository reviewRepository;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

		String method = request.getMethod();

		if (method.equalsIgnoreCase("GET")) {
			return true;
		}

		Long requestMemberId = (Long) request.getAttribute("memberId");
		if (requestMemberId == null) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "로그인이 필요합니다.");
			return false;
		}

		if (method.equalsIgnoreCase("PATCH") || method.equalsIgnoreCase("DELETE")) {
			Map<String, String> pathVariables = (Map<String, String>)request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
			Long reviewId;

			try {
				reviewId = Long.parseLong(pathVariables.get("reviewId"));
			} catch (NumberFormatException e) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "유효하지 않은 리뷰 ID입니다.");
				return false;
			}

			Long authorId = reviewRepository.findMemberIdByReviewId(reviewId).
				orElse(null);

			if (authorId == null || !authorId.equals(requestMemberId)) {
				response.sendError(HttpServletResponse.SC_FORBIDDEN, "본인의 리뷰만 수정 또는 삭제할 수 있습니다.");
				return false;
			}
		}

		return true;
	}
}
