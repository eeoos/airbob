package kr.kro.airbob.domain.payment.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.exception.TossPaymentConfirmException;
import kr.kro.airbob.domain.payment.exception.code.PaymentConfirmErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentsAdapter {

	public static final String PAYMENT_KEY = "paymentKey";
	public static final String ORDER_ID = "orderId";
	public static final String AMOUNT = "amount";
	public static final String PARSING_FAILED_CODE = "PARSING_FAILED";
	public static final String CONFIRM_PATH = "/v1/payments/confirm";

	private final RestClient tossPaymentsRestClient;
	private final ObjectMapper objectMapper;

	public TossPaymentResponse confirmPayment(String paymentKey, String orderId, Integer amount) {
		Map<String, Object> payload = new HashMap<>();
		payload.put(PAYMENT_KEY, paymentKey);
		payload.put(ORDER_ID, orderId);
		payload.put(AMOUNT, amount);

		return Objects.requireNonNull(
			tossPaymentsRestClient.post()
				.uri(CONFIRM_PATH)
				.body(payload)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					String errorBody = new String(response.getBody().readAllBytes());
					TossPaymentResponse errorResponse = parseErrorResponse(errorBody);

					String errorCode = errorResponse.getFailure() != null ? errorResponse.getFailure().getCode() : "UNKNOWN_ERROR";
					PaymentConfirmErrorCode confirmErrorCode = PaymentConfirmErrorCode.fromErrorCode(errorCode);

					throw new TossPaymentConfirmException(confirmErrorCode);
				})
				.toEntity(TossPaymentResponse.class)
				.getBody()
		);
	}

	private TossPaymentResponse parseErrorResponse(String errorBody) {
		try {
			return objectMapper.readValue(errorBody, TossPaymentResponse.class);
		} catch (IOException e) {
			log.error("토스페이먼츠 에러 응답 파싱 실패", e);
			return TossPaymentResponse.builder()
					.failure(TossPaymentResponse.Failure.builder()
						.code(PARSING_FAILED_CODE)
						.message(errorBody)
						.build())
						.build();
		}
	}
}
