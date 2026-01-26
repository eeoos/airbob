package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TossPaymentsAdapter 테스트")
class TossPaymentsAdapterTest {

	@Nested
	@DisplayName("상수 값 검증 테스트")
	class ConstantsTest {

		@Test
		@DisplayName("VALID_HOURS_VALUE는 24이다")
		void VALID_HOURS_VALUE_검증() {
			assertThat(TossPaymentsAdapter.VALID_HOURS_VALUE).isEqualTo(24);
		}

		@Test
		@DisplayName("CONFIRM_PATH가 올바르게 설정되어 있다")
		void CONFIRM_PATH_검증() {
			assertThat(TossPaymentsAdapter.CONFIRM_PATH).isEqualTo("/v1/payments/confirm");
		}

		@Test
		@DisplayName("CANCEL_PATH가 올바르게 설정되어 있다")
		void CANCEL_PATH_검증() {
			assertThat(TossPaymentsAdapter.CANCEL_PATH).isEqualTo("/v1/payments/{paymentKey}/cancel");
		}

		@Test
		@DisplayName("VIRTUAL_ACCOUNTS_PATH가 올바르게 설정되어 있다")
		void VIRTUAL_ACCOUNTS_PATH_검증() {
			assertThat(TossPaymentsAdapter.VIRTUAL_ACCOUNTS_PATH).isEqualTo("/v1/virtual-accounts");
		}

		@Test
		@DisplayName("GET_PATH_BY_PAYMENT_KEY가 올바르게 설정되어 있다")
		void GET_PATH_BY_PAYMENT_KEY_검증() {
			assertThat(TossPaymentsAdapter.GET_PATH_BY_PAYMENT_KEY).isEqualTo("/v1/payments/{paymentKey}");
		}

		@Test
		@DisplayName("GET_PATH_BY_ORDER_ID가 올바르게 설정되어 있다")
		void GET_PATH_BY_ORDER_ID_검증() {
			assertThat(TossPaymentsAdapter.GET_PATH_BY_ORDER_ID).isEqualTo("/v1/payments/orders/{orderId}");
		}

		@Test
		@DisplayName("PAYMENT_KEY 상수가 올바르게 설정되어 있다")
		void PAYMENT_KEY_검증() {
			assertThat(TossPaymentsAdapter.PAYMENT_KEY).isEqualTo("paymentKey");
		}

		@Test
		@DisplayName("ORDER_ID 상수가 올바르게 설정되어 있다")
		void ORDER_ID_검증() {
			assertThat(TossPaymentsAdapter.ORDER_ID).isEqualTo("orderId");
		}

		@Test
		@DisplayName("AMOUNT 상수가 올바르게 설정되어 있다")
		void AMOUNT_검증() {
			assertThat(TossPaymentsAdapter.AMOUNT).isEqualTo("amount");
		}

		@Test
		@DisplayName("CANCEL_REASON 상수가 올바르게 설정되어 있다")
		void CANCEL_REASON_검증() {
			assertThat(TossPaymentsAdapter.CANCEL_REASON).isEqualTo("cancelReason");
		}

		@Test
		@DisplayName("CANCEL_AMOUNT 상수가 올바르게 설정되어 있다")
		void CANCEL_AMOUNT_검증() {
			assertThat(TossPaymentsAdapter.CANCEL_AMOUNT).isEqualTo("cancelAmount");
		}

		@Test
		@DisplayName("BANK 상수가 올바르게 설정되어 있다")
		void BANK_검증() {
			assertThat(TossPaymentsAdapter.BANK).isEqualTo("bank");
		}

		@Test
		@DisplayName("CUSTOMER_NAME 상수가 올바르게 설정되어 있다")
		void CUSTOMER_NAME_검증() {
			assertThat(TossPaymentsAdapter.CUSTOMER_NAME).isEqualTo("customerName");
		}

		@Test
		@DisplayName("VALID_HOURS 상수가 올바르게 설정되어 있다")
		void VALID_HOURS_검증() {
			assertThat(TossPaymentsAdapter.VALID_HOURS).isEqualTo("validHours");
		}

		@Test
		@DisplayName("UNKNOWN_ERROR 상수가 올바르게 설정되어 있다")
		void UNKNOWN_ERROR_검증() {
			assertThat(TossPaymentsAdapter.UNKNOWN_ERROR).isEqualTo("UNKNOWN_ERROR");
		}

		@Test
		@DisplayName("TOSS_API_SERVER_ERROR 상수가 올바르게 설정되어 있다")
		void TOSS_API_SERVER_ERROR_검증() {
			assertThat(TossPaymentsAdapter.TOSS_API_SERVER_ERROR).isEqualTo("토스 페이먼츠 API 서버 에러: ");
		}
	}
}
