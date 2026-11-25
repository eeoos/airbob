package kr.kro.airbob.core.domain.payment;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PaymentMethod 열거형 테스트")
class PaymentMethodTest {

	@Nested
	@DisplayName("fromDescription 메서드 테스트")
	class FromDescriptionTest {

		@Test
		@DisplayName("카드 설명으로 CARD 반환")
		void mapsCard() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription("카드");

			// then
			assertThat(method).isEqualTo(PaymentMethod.CARD);
		}

		@Test
		@DisplayName("가상계좌 설명으로 VIRTUAL_ACCOUNT 반환")
		void mapsVirtualAccount() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription("가상계좌");

			// then
			assertThat(method).isEqualTo(PaymentMethod.VIRTUAL_ACCOUNT);
		}

		@Test
		@DisplayName("간편결제 설명으로 EASY_PAY 반환")
		void mapsEasyPay() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription("간편결제");

			// then
			assertThat(method).isEqualTo(PaymentMethod.EASY_PAY);
		}

		@Test
		@DisplayName("휴대폰 설명으로 MOBILE_PHONE 반환")
		void mapsMobilePhone() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription("휴대폰");

			// then
			assertThat(method).isEqualTo(PaymentMethod.MOBILE_PHONE);
		}

		@Test
		@DisplayName("계좌이체 설명으로 BANK_TRANSFER 반환")
		void mapsBankTransfer() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription("계좌이체");

			// then
			assertThat(method).isEqualTo(PaymentMethod.BANK_TRANSFER);
		}

		@Test
		@DisplayName("문화상품권 설명으로 CULTURE_GIFT_CARD 반환")
		void mapsCultureGiftCard() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription("문화상품권");

			// then
			assertThat(method).isEqualTo(PaymentMethod.CULTURE_GIFT_CARD);
		}

		@Test
		@DisplayName("도서문화상품권 설명으로 BOOK_GIFT_CARD 반환")
		void mapsBookGiftCard() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription("도서문화상품권");

			// then
			assertThat(method).isEqualTo(PaymentMethod.BOOK_GIFT_CARD);
		}

		@Test
		@DisplayName("게임문화상품권 설명으로 GAME_GIFT_CARD 반환")
		void mapsGameGiftCard() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription("게임문화상품권");

			// then
			assertThat(method).isEqualTo(PaymentMethod.GAME_GIFT_CARD);
		}

		@Test
		@DisplayName("알 수 없는 설명으로 UNKNOWN 반환")
		void mapsUnknownDescriptionToUnknown() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription("알 수 없는 결제 방식");

			// then
			assertThat(method).isEqualTo(PaymentMethod.UNKNOWN);
		}

		@Test
		@DisplayName("null 입력 시 UNKNOWN 반환")
		void mapsNullToUnknown() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription(null);

			// then
			assertThat(method).isEqualTo(PaymentMethod.UNKNOWN);
		}

		@Test
		@DisplayName("빈 문자열 입력 시 UNKNOWN 반환")
		void mapsEmptyStringToUnknown() {
			// when
			PaymentMethod method = PaymentMethod.fromDescription("");

			// then
			assertThat(method).isEqualTo(PaymentMethod.UNKNOWN);
		}
	}

	@Nested
	@DisplayName("description getter 테스트")
	class GetDescriptionTest {

		@Test
		@DisplayName("CARD의 description은 '카드'이다")
		void CARD_description() {
			// when
			String description = PaymentMethod.CARD.getDescription();

			// then
			assertThat(description).isEqualTo("카드");
		}

		@Test
		@DisplayName("UNKNOWN의 description은 '알수없음'이다")
		void UNKNOWN_description() {
			// when
			String description = PaymentMethod.UNKNOWN.getDescription();

			// then
			assertThat(description).isEqualTo("알수없음");
		}
	}
}
