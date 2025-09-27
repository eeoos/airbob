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
import kr.kro.airbob.domain.payment.exception.TossPaymentCancelException;
import kr.kro.airbob.domain.payment.exception.TossPaymentConfirmException;
import kr.kro.airbob.domain.payment.exception.TossPaymentInquiryException;
import kr.kro.airbob.domain.payment.exception.code.PaymentCancelErrorCode;
import kr.kro.airbob.domain.payment.exception.code.PaymentConfirmErrorCode;
import kr.kro.airbob.domain.payment.exception.code.PaymentInquiryErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentsAdapter {

	public static final String PAYMENT_KEY = "paymentKey";
	public static final String ORDER_ID = "orderId";
	public static final String AMOUNT = "amount";
	public static final String CANCEL_REASON = "cancelReason";
	public static final String UNKNOWN_ERROR = "UNKNOWN_ERROR";
	public static final String PARSING_FAILED_CODE = "PARSING_FAILED";
	public static final String CONFIRM_PATH = "/v1/payments/confirm";
	public static final String CANCEL_PATH = "/v1/payments/{paymentKey}/cancel";
	public static final String CANCEL_AMOUNT = "cancelAmount";
	public static final String GET_PATH_BY_PAYMENT_KEY = "/v1/payments/{paymentKey}";
	public static final String GET_PATH_BY_ORDER_ID = "/v1/payments/orders/{orderId}";

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

					String errorCode = errorResponse.getFailure() != null ? errorResponse.getFailure().getCode() :
						UNKNOWN_ERROR;
					PaymentConfirmErrorCode confirmErrorCode = PaymentConfirmErrorCode.fromErrorCode(errorCode);

					throw new TossPaymentConfirmException(confirmErrorCode);
				})
				.toEntity(TossPaymentResponse.class)
				.getBody()
		);
	}

	public TossPaymentResponse cancelPayment(String paymentKey, String cancelReason, Long cancelAmount) {
		Map<String, Object> payload = new HashMap<>();
		payload.put(CANCEL_REASON, cancelReason);

		if (cancelAmount != null) {
			payload.put(CANCEL_AMOUNT, cancelAmount);
		}

		return Objects.requireNonNull(
			tossPaymentsRestClient.post()
				.uri(CANCEL_PATH, paymentKey)
				.body(payload)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					String errorBody = new String(response.getBody().readAllBytes());
					TossPaymentResponse errorResponse = parseErrorResponse(errorBody);

					String errorCode = errorResponse.getFailure() != null ? errorResponse.getFailure().getCode() :
						UNKNOWN_ERROR;
					PaymentCancelErrorCode cancelErrorCode = PaymentCancelErrorCode.fromErrorCode(errorCode);

					throw new TossPaymentCancelException(cancelErrorCode);
				})
				.toEntity(TossPaymentResponse.class)
				.getBody()
		);
	}

	public TossPaymentResponse getPaymentByPaymentKey(String paymentKey) {
		return tossPaymentsRestClient.get()
			.uri(GET_PATH_BY_PAYMENT_KEY, paymentKey)
			.retrieve()
			.onStatus(HttpStatusCode::isError, (request, response) -> {
				String errorBody = new String(response.getBody().readAllBytes());
				TossPaymentResponse errorResponse = parseErrorResponse(errorBody);

				String errorCode = errorResponse.getFailure() != null ? errorResponse.getFailure().getCode() :
					UNKNOWN_ERROR;
				PaymentInquiryErrorCode inquiryErrorCode = PaymentInquiryErrorCode.fromErrorCode(errorCode);

				throw new TossPaymentInquiryException(inquiryErrorCode);
			})
			.body(TossPaymentResponse.class);
	}

	public TossPaymentResponse getPaymentByOrderId(String orderId) {
		return tossPaymentsRestClient.get()
			.uri(GET_PATH_BY_ORDER_ID, orderId)
			.retrieve()
			.onStatus(HttpStatusCode::isError, (request, response) -> {
				String errorBody = new String(response.getBody().readAllBytes());
				TossPaymentResponse errorResponse = parseErrorResponse(errorBody);

				String errorCode = errorResponse.getFailure() != null ? errorResponse.getFailure().getCode() :
					UNKNOWN_ERROR;
				PaymentInquiryErrorCode inquiryErrorCode = PaymentInquiryErrorCode.fromErrorCode(errorCode);

				throw new TossPaymentInquiryException(inquiryErrorCode);
			})
			.body(TossPaymentResponse.class);
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
