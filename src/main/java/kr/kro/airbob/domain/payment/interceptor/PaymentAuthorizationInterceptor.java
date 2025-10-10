package kr.kro.airbob.domain.payment.interceptor;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentAuthorizationInterceptor implements HandlerInterceptor {

	private final PaymentRepository paymentRepository;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws
		Exception {

		Map<String, String> pathVariables = (Map<String, String>)request.getAttribute(
			HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

		if (pathVariables == null || pathVariables.isEmpty()) {
			// /api/v1/confirm은 통과
			return true;
		}

		Long currentMemberId = UserContext.get().id();
		Long ownerId = null;

		if (pathVariables.containsKey("paymentKey")) {
			String paymentKey = pathVariables.get("paymentKey");
			ownerId = paymentRepository.findGuestIdByPaymentKey(paymentKey).orElse(null);
		} else if (pathVariables.containsKey("orderId")) {
			String orderId = pathVariables.get("orderId");
			ownerId = paymentRepository.findGuestIdByOrderId(orderId).orElse(null);
		}

		if (ownerId == null || !ownerId.equals(currentMemberId)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "해당 결제 정보에 대한 접근 권한이 없습니다.");
			return false;
		}

		return true;
	}
}
