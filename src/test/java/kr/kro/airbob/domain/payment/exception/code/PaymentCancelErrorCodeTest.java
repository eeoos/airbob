package kr.kro.airbob.domain.payment.exception.code;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

@DisplayName("PaymentCancelErrorCode 테스트")
class PaymentCancelErrorCodeTest {

	@Nested
	@DisplayName("fromErrorCode 메서드 테스트")
	class FromErrorCodeTest {

		@Test
		@DisplayName("ALREADY_CANCELED_PAYMENT 코드 매핑")
		void ALREADY_CANCELED_PAYMENT_매핑() {
			// when
			PaymentCancelErrorCode result = PaymentCancelErrorCode.fromErrorCode("ALREADY_CANCELED_PAYMENT");

			// then
			assertThat(result).isEqualTo(PaymentCancelErrorCode.ALREADY_CANCELED_PAYMENT);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@DisplayName("NOT_CANCELABLE_AMOUNT 코드 매핑")
		void NOT_CANCELABLE_AMOUNT_매핑() {
			// when
			PaymentCancelErrorCode result = PaymentCancelErrorCode.fromErrorCode("NOT_CANCELABLE_AMOUNT");

			// then
			assertThat(result).isEqualTo(PaymentCancelErrorCode.NOT_CANCELABLE_AMOUNT);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		}

		@Test
		@DisplayName("NOT_FOUND_PAYMENT 코드 매핑")
		void NOT_FOUND_PAYMENT_매핑() {
			// when
			PaymentCancelErrorCode result = PaymentCancelErrorCode.fromErrorCode("NOT_FOUND_PAYMENT");

			// then
			assertThat(result).isEqualTo(PaymentCancelErrorCode.NOT_FOUND_PAYMENT);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@DisplayName("FAILED_REFUND_PROCESS 코드 매핑")
		void FAILED_REFUND_PROCESS_매핑() {
			// when
			PaymentCancelErrorCode result = PaymentCancelErrorCode.fromErrorCode("FAILED_REFUND_PROCESS");

			// then
			assertThat(result).isEqualTo(PaymentCancelErrorCode.FAILED_REFUND_PROCESS);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}

		@Test
		@DisplayName("알 수 없는 코드는 MISMATCH_ERROR로 매핑된다")
		void 알수없는_코드_MISMATCH_ERROR() {
			// when
			PaymentCancelErrorCode result = PaymentCancelErrorCode.fromErrorCode("UNKNOWN_CANCEL_ERROR");

			// then
			assertThat(result).isEqualTo(PaymentCancelErrorCode.PAYMENT_CANCEL_ERROR_MISMATCH_ERROR);
		}

		@Test
		@DisplayName("대소문자 구분 없이 매핑된다")
		void 대소문자_무관() {
			// when
			PaymentCancelErrorCode upper = PaymentCancelErrorCode.fromErrorCode("INVALID_REQUEST");
			PaymentCancelErrorCode lower = PaymentCancelErrorCode.fromErrorCode("invalid_request");
			PaymentCancelErrorCode mixed = PaymentCancelErrorCode.fromErrorCode("Invalid_Request");

			// then
			assertThat(upper).isEqualTo(PaymentCancelErrorCode.INVALID_REQUEST);
			assertThat(lower).isEqualTo(PaymentCancelErrorCode.INVALID_REQUEST);
			assertThat(mixed).isEqualTo(PaymentCancelErrorCode.INVALID_REQUEST);
		}

		@ParameterizedTest
		@ValueSource(strings = {
			"ALREADY_CANCELED_PAYMENT",
			"INVALID_REFUND_ACCOUNT_INFO",
			"INVALID_REFUND_ACCOUNT_NUMBER",
			"INVALID_BANK",
			"ALREADY_REFUND_PAYMENT"
		})
		@DisplayName("400 Bad Request 에러 코드들이 올바르게 매핑된다")
		void BAD_REQUEST_에러코드들(String errorCode) {
			// when
			PaymentCancelErrorCode result = PaymentCancelErrorCode.fromErrorCode(errorCode);

			// then
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@ParameterizedTest
		@ValueSource(strings = {
			"NOT_CANCELABLE_AMOUNT",
			"FORBIDDEN_CONSECUTIVE_REQUEST",
			"NOT_CANCELABLE_PAYMENT",
			"EXCEED_MAX_REFUND_DUE",
			"NOT_ALLOWED_PARTIAL_REFUND"
		})
		@DisplayName("403 Forbidden 에러 코드들이 올바르게 매핑된다")
		void FORBIDDEN_에러코드들(String errorCode) {
			// when
			PaymentCancelErrorCode result = PaymentCancelErrorCode.fromErrorCode(errorCode);

			// then
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		}

		@ParameterizedTest
		@ValueSource(strings = {
			"FAILED_INTERNAL_SYSTEM_PROCESSING",
			"FAILED_REFUND_PROCESS",
			"FAILED_METHOD_HANDLING_CANCEL",
			"FAILED_PARTIAL_REFUND",
			"COMMON_ERROR"
		})
		@DisplayName("500 Internal Server Error 에러 코드들이 올바르게 매핑된다")
		void INTERNAL_SERVER_ERROR_에러코드들(String errorCode) {
			// when
			PaymentCancelErrorCode result = PaymentCancelErrorCode.fromErrorCode(errorCode);

			// then
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@Nested
	@DisplayName("TossErrorCode 인터페이스 구현 테스트")
	class TossErrorCodeInterfaceTest {

		@Test
		@DisplayName("getStatusCode가 올바른 HttpStatus를 반환한다")
		void getStatusCode_테스트() {
			// given
			PaymentCancelErrorCode errorCode = PaymentCancelErrorCode.NOT_CANCELABLE_PAYMENT;

			// when
			HttpStatus statusCode = errorCode.getStatusCode();

			// then
			assertThat(statusCode).isEqualTo(HttpStatus.FORBIDDEN);
		}

		@Test
		@DisplayName("getErrorCode가 에러 메시지를 반환한다")
		void getErrorCode_테스트() {
			// given
			PaymentCancelErrorCode errorCode = PaymentCancelErrorCode.NOT_CANCELABLE_PAYMENT;

			// when
			String message = errorCode.getErrorCode();

			// then
			assertThat(message).contains("취소");
		}

		@Test
		@DisplayName("name()이 enum 상수명을 반환한다")
		void name_테스트() {
			// given
			PaymentCancelErrorCode errorCode = PaymentCancelErrorCode.NOT_CANCELABLE_PAYMENT;

			// when
			String name = errorCode.name();

			// then
			assertThat(name).isEqualTo("NOT_CANCELABLE_PAYMENT");
		}
	}

	@Nested
	@DisplayName("에러 메시지 검증 테스트")
	class ErrorMessageTest {

		@Test
		@DisplayName("EXCEED_MAX_REFUND_DUE 에러 메시지 검증")
		void EXCEED_MAX_REFUND_DUE_메시지() {
			// when
			String message = PaymentCancelErrorCode.EXCEED_MAX_REFUND_DUE.getErrorCode();

			// then
			assertThat(message).contains("환불 가능한 기간");
		}

		@Test
		@DisplayName("NOT_AVAILABLE_BANK 에러 메시지 검증")
		void NOT_AVAILABLE_BANK_메시지() {
			// when
			String message = PaymentCancelErrorCode.NOT_AVAILABLE_BANK.getErrorCode();

			// then
			assertThat(message).contains("은행 서비스 시간");
		}
	}
}
