package kr.kro.airbob.domain.review.interceptor;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReviewAuthorizationInterceptor implements HandlerInterceptor {

	public static final int ACCOMMODATION_ID_INDEX = 3;
	public static final int REVIEW_ID_INDEX = 5;
	private final ReviewRepository reviewRepository;
	private final ReservationRepository reservationRepository;
	private final AccommodationRepository accommodationRepository;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws
		Exception {

		String method = request.getMethod();
		String uri = request.getRequestURI();

		String[] segments = uri.split("/");
		Long accommodationId;
		try { // base uri: /api/accommodations/{accommodationId}/reviews
			accommodationId = Long.parseLong(segments[ACCOMMODATION_ID_INDEX]);
		} catch (NumberFormatException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "유효하지 않은 숙소 ID입니다.");
			return false;
		}

		// 존재하지 않는 숙소
		if (!accommodationRepository.existsById(accommodationId)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "숙소를 찾을 수 없습니다.");
			return false;
		}

		// -- 공통 검증 end --

		if (method.equalsIgnoreCase("GET")) {
			return true; // 리뷰 조회 통과
		}

		// -- 전체 조회/프리뷰 end --

		// memberId 확인 및 설정
		Long requestMemberId = (Long)request.getAttribute("memberId");
		if (requestMemberId == null) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "로그인이 필요합니다.");
			return false;
		}

		// -- 로그인 검증 end --

		if (!reservationRepository.existsCompletedReservationByGuest(accommodationId, requestMemberId)) { // 수정된 코드
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "숙소를 예약한 사용자만 리뷰를 작성할 수 있습니다.");
			return false;
		}

		// -- 숙소 예약자 여부 --

		if (method.equalsIgnoreCase("POST")) { // 리뷰 작성
			if (reviewRepository.existsByAccommodationIdAndAuthorId(accommodationId, requestMemberId)) { // 이미 작성했으면 409
				response.sendError(HttpServletResponse.SC_CONFLICT, "이미 해당 숙소에 대한 리뷰를 작성하셨습니다.");
				return false;
			}
			return true;
		}

		Long reviewId;
		try { // base uri: /api/accommodations/{accommodationId}/reviews
			reviewId = Long.parseLong(segments[REVIEW_ID_INDEX]);
		} catch (NumberFormatException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "유효하지 않은 리뷰 ID입니다.");
			return false;
		}

		Long reviewedAccommodationId = reviewRepository.findAccommodationIdByReviewId(reviewId);
		if (reviewedAccommodationId == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "리뷰를 찾을 수 없습니다.");
			return false;
		}

		Long reviewerId = reviewRepository.findMemberIdByReviewId(reviewId);
		if (!requestMemberId.equals(reviewerId)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "본인의 리뷰만 수정 또는 삭제할 수 있습니다.");
			return false;
		}

		if (!accommodationId.equals(reviewedAccommodationId)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "해당 숙소의 리뷰가 아닙니다.");
			return false;
		}

		return true;
	}
}
