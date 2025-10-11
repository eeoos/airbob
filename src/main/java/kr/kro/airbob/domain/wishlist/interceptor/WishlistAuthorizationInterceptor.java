package kr.kro.airbob.domain.wishlist.interceptor;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class WishlistAuthorizationInterceptor implements HandlerInterceptor {

	public static final int WISHLIST_ID_INDEX = 4;
	public static final int WISHLIST_ACCOMMODATION_ID_INDEX = 6;
	private final WishlistRepository wishlistRepository;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		Map<String, String> pathVariables = (Map<String, String>)request.getAttribute(
			HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

		// wishlist가 없는 경로는 소유권 검사 대상 X
		if (pathVariables == null || !pathVariables.containsKey("wishlistId")) {
			return true;
		}

		Long wishlistId;
		try {
			wishlistId = Long.parseLong(pathVariables.get("wishlistId"));
		} catch (NumberFormatException e) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "유효하지 않은 위시리스트 ID입니다.");
			return false;
		}

		Long currentMemberId = UserContext.get().id();

		if (!wishlistRepository.existsByIdAndMemberId(wishlistId, currentMemberId)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "해당 위시리스트에 대한 접근 권한이 없습니다.");
			return false;
		}

		return true;
	}
}
