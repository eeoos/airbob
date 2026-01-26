package kr.kro.airbob.domain.payment.exception.code;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

@DisplayName("PaymentConfirmErrorCode 테스트")
class PaymentConfirmErrorCodeTest {

	@Nested
	@DisplayName("fromErrorCode 메서드 테스트")
	class FromErrorCodeTest {

		@Test
		@DisplayName("ALREADY_PROCESSED_PAYMENT 코드 매핑")
		void ALREADY_PROCESSED_PAYMENT_매핑() {
			// when
			PaymentConfirmErrorCode result = PaymentConfirmErrorCode.fromErrorCode("ALREADY_PROCESSED_PAYMENT");

			// then
			assertThat(result).isEqualTo(PaymentConfirmErrorCode.ALREADY_PROCESSED_PAYMENT);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@Test
		@DisplayName("UNAUTHORIZED_KEY 코드 매핑")
		void UNAUTHORIZED_KEY_매핑() {
			// when
			PaymentConfirmErrorCode result = PaymentConfirmErrorCode.fromErrorCode("UNAUTHORIZED_KEY");

			// then
			assertThat(result).isEqualTo(PaymentConfirmErrorCode.UNAUTHORIZED_KEY);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}

		@Test
		@DisplayName("REJECT_CARD_PAYMENT 코드 매핑")
		void REJECT_CARD_PAYMENT_매핑() {
			// when
			PaymentConfirmErrorCode result = PaymentConfirmErrorCode.fromErrorCode("REJECT_CARD_PAYMENT");

			// then
			assertThat(result).isEqualTo(PaymentConfirmErrorCode.REJECT_CARD_PAYMENT);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		}

		@Test
		@DisplayName("NOT_FOUND_PAYMENT 코드 매핑")
		void NOT_FOUND_PAYMENT_매핑() {
			// when
			PaymentConfirmErrorCode result = PaymentConfirmErrorCode.fromErrorCode("NOT_FOUND_PAYMENT");

			// then
			assertThat(result).isEqualTo(PaymentConfirmErrorCode.NOT_FOUND_PAYMENT);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}

		@Test
		@DisplayName("FAILED_INTERNAL_SYSTEM_PROCESSING 코드 매핑")
		void FAILED_INTERNAL_SYSTEM_PROCESSING_매핑() {
			// when
			PaymentConfirmErrorCode result = PaymentConfirmErrorCode.fromErrorCode("FAILED_INTERNAL_SYSTEM_PROCESSING");

			// then
			assertThat(result).isEqualTo(PaymentConfirmErrorCode.FAILED_INTERNAL_SYSTEM_PROCESSING);
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		}

		@Test
		@DisplayName("알 수 없는 코드는 MISMATCH_ERROR로 매핑된다")
		void 알수없는_코드_MISMATCH_ERROR() {
			// when
			PaymentConfirmErrorCode result = PaymentConfirmErrorCode.fromErrorCode("UNKNOWN_ERROR_CODE");

			// then
			assertThat(result).isEqualTo(PaymentConfirmErrorCode.PAYMENT_CONFIRM_ERROR_MISMATCH_ERROR);
		}

		@Test
		@DisplayName("대소문자 구분 없이 매핑된다")
		void 대소문자_무관() {
			// when
			PaymentConfirmErrorCode upper = PaymentConfirmErrorCode.fromErrorCode("INVALID_REQUEST");
			PaymentConfirmErrorCode lower = PaymentConfirmErrorCode.fromErrorCode("invalid_request");
			PaymentConfirmErrorCode mixed = PaymentConfirmErrorCode.fromErrorCode("Invalid_Request");

			// then
			assertThat(upper).isEqualTo(PaymentConfirmErrorCode.INVALID_REQUEST);
			assertThat(lower).isEqualTo(PaymentConfirmErrorCode.INVALID_REQUEST);
			assertThat(mixed).isEqualTo(PaymentConfirmErrorCode.INVALID_REQUEST);
		}

		@ParameterizedTest
		@ValueSource(strings = {
			"BELOW_MINIMUM_AMOUNT",
			"EXCEED_MAX_AMOUNT",
			"INVALID_CARD_NUMBER",
			"INVALID_CARD_EXPIRATION",
			"EXCEED_MAX_DAILY_PAYMENT_COUNT"
		})
		@DisplayName("400 Bad Request 에러 코드들이 올바르게 매핑된다")
		void BAD_REQUEST_에러코드들(String errorCode) {
			// when
			PaymentConfirmErrorCode result = PaymentConfirmErrorCode.fromErrorCode(errorCode);

			// then
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}

		@ParameterizedTest
		@ValueSource(strings = {
			"REJECT_ACCOUNT_PAYMENT",
			"REJECT_CARD_COMPANY",
			"FORBIDDEN_REQUEST",
			"EXCEED_MAX_AUTH_COUNT",
			"FDS_ERROR"
		})
		@DisplayName("403 Forbidden 에러 코드들이 올바르게 매핑된다")
		void FORBIDDEN_에러코드들(String errorCode) {
			// when
			PaymentConfirmErrorCode result = PaymentConfirmErrorCode.fromErrorCode(errorCode);

			// then
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	@Nested
	@DisplayName("TossErrorCode 인터페이스 구현 테스트")
	class TossErrorCodeInterfaceTest {

		@Test
		@DisplayName("getStatusCode가 올바른 HttpStatus를 반환한다")
		void getStatusCode_테스트() {
			// given
			PaymentConfirmErrorCode errorCode = PaymentConfirmErrorCode.REJECT_CARD_PAYMENT;

			// when
			HttpStatus statusCode = errorCode.getStatusCode();

			// then
			assertThat(statusCode).isEqualTo(HttpStatus.FORBIDDEN);
		}

		@Test
		@DisplayName("getErrorCode가 에러 메시지를 반환한다")
		void getErrorCode_테스트() {
			// given
			PaymentConfirmErrorCode errorCode = PaymentConfirmErrorCode.REJECT_CARD_PAYMENT;

			// when
			String message = errorCode.getErrorCode();

			// then
			assertThat(message).contains("잔액부족");
		}

		@Test
		@DisplayName("name()이 enum 상수명을 반환한다")
		void name_테스트() {
			// given
			PaymentConfirmErrorCode errorCode = PaymentConfirmErrorCode.REJECT_CARD_PAYMENT;

			// when
			String name = errorCode.name();

			// then
			assertThat(name).isEqualTo("REJECT_CARD_PAYMENT");
		}
	}
}
