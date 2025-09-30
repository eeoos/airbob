package kr.kro.airbob.domain.payment.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatusCode;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.exception.TossPaymentCancelException;
import kr.kro.airbob.domain.payment.exception.TossPaymentConfirmException;
import kr.kro.airbob.domain.payment.exception.TossPaymentInquiryException;
import kr.kro.airbob.domain.payment.exception.VirtualAccountIssueException;
import kr.kro.airbob.domain.payment.exception.code.PaymentCancelErrorCode;
import kr.kro.airbob.domain.payment.exception.code.PaymentConfirmErrorCode;
import kr.kro.airbob.domain.payment.exception.code.PaymentInquiryErrorCode;
import kr.kro.airbob.domain.payment.exception.code.VirtualAccountIssueErrorCode;
import kr.kro.airbob.domain.reservation.entity.Reservation;
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
	public static final String BANK = "bank";
	public static final String CUSTOMER_NAME = "customerName";
	public static final int VALID_HOURS_VALUE = 24;
	public static final String UNKNOWN_ERROR = "UNKNOWN_ERROR";
	public static final String PARSING_FAILED_CODE = "PARSING_FAILED";
	public static final String CONFIRM_PATH = "/v1/payments/confirm";
	public static final String CANCEL_PATH = "/v1/payments/{paymentKey}/cancel";
	public static final String CANCEL_AMOUNT = "cancelAmount";
	public static final String GET_PATH_BY_PAYMENT_KEY = "/v1/payments/{paymentKey}";
	public static final String GET_PATH_BY_ORDER_ID = "/v1/payments/orders/{orderId}";
	public static final String VALID_HOURS = "validHours";
	public static final String VIRTUAL_ACCOUNTS_PATH = "/v1/virtual-accounts";
	public static final String TOSS_API_SERVER_ERROR = "토스 페이먼츠 API 서버 에러: ";

	private final RestClient tossPaymentsRestClient;
	private final ObjectMapper objectMapper;

	@Retryable(
		retryFor = { ResourceAccessException.class },
		maxAttempts = 3,
		backoff = @Backoff(delay = 2000)
	)
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
				.onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
					throw new ResourceAccessException(TOSS_API_SERVER_ERROR + response.getStatusCode());
				})
				.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
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

	@Retryable(
		retryFor = { ResourceAccessException.class },
		maxAttempts = 3,
		backoff = @Backoff(delay = 2000)
	)
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
				.onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
					throw new ResourceAccessException(TOSS_API_SERVER_ERROR + response.getStatusCode());
				})
				.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
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
		return getPayment(GET_PATH_BY_PAYMENT_KEY, paymentKey);
	}

	public TossPaymentResponse getPaymentByOrderId(String orderId) {
		return getPayment(GET_PATH_BY_ORDER_ID, orderId);
	}

	// TODO: 도메인 재발급 후 웹훅 구현 필요
	@Retryable(
		retryFor = { ResourceAccessException.class },
		maxAttempts = 3,
		backoff = @Backoff(delay = 2000)
	)
	public TossPaymentResponse issueVirtualAccount(Reservation reservation,String bankCode, String customerName) {
		Map<String, Object> payload = new HashMap<>();
		payload.put(AMOUNT, reservation.getTotalPrice());
		payload.put(ORDER_ID, reservation.getReservationUid().toString());
		payload.put(BANK, bankCode);
		payload.put(CUSTOMER_NAME, customerName);
		payload.put(VALID_HOURS, VALID_HOURS_VALUE); // 24시간으로 제한

		return Objects.requireNonNull(
			tossPaymentsRestClient.post()
				.uri(VIRTUAL_ACCOUNTS_PATH)
				.body(payload)
				.retrieve()
				.onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
					throw new ResourceAccessException(TOSS_API_SERVER_ERROR + response.getStatusCode());
				})
				.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
					String errorBody = new String(response.getBody().readAllBytes());
					TossPaymentResponse errorResponse = parseErrorResponse(errorBody);
					String errorCode =
						errorResponse.getFailure() != null ? errorResponse.getFailure().getCode() : UNKNOWN_ERROR;

					VirtualAccountIssueErrorCode virtualAccountIssueErrorCode = VirtualAccountIssueErrorCode.fromErrorCode(
						errorCode);

					throw new VirtualAccountIssueException(virtualAccountIssueErrorCode);
				})
				.toEntity(TossPaymentResponse.class)
				.getBody()
		);
	}

	@Retryable(
		retryFor = { ResourceAccessException.class },
		maxAttempts = 3,
		backoff = @Backoff(delay = 2000)
	)
	private TossPaymentResponse getPayment(String path, String id) {
		return tossPaymentsRestClient.get()
			.uri(path, id)
			.retrieve()
			.onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
				throw new ResourceAccessException(TOSS_API_SERVER_ERROR + response.getStatusCode());
			})
			.onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
				String errorBody = new String(response.getBody().readAllBytes());
				TossPaymentResponse errorResponse = parseErrorResponse(errorBody);

				String errorCode = errorResponse.getFailure() != null ? errorResponse.getFailure().getCode() :
					UNKNOWN_ERROR;
				PaymentInquiryErrorCode inquiryErrorCode = PaymentInquiryErrorCode.fromErrorCode(errorCode);

				throw new TossPaymentInquiryException(inquiryErrorCode);
			})
			.body(TossPaymentResponse.class);
	}

	private TossPaymentResponse parseErrorResponse(String errorBody)  throws IOException {
		try {
			return objectMapper.readValue(errorBody, TossPaymentResponse.class);
		} catch (JsonProcessingException e) {
			log.error("토스페이먼츠 에러 응답 파싱 실패", e);
			throw new IOException("토스 페이먼츠 응답 파싱 실패: " + errorBody, e);
		}
	}
}
