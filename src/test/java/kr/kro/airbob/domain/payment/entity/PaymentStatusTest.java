package kr.kro.airbob.domain.payment.entity;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PaymentStatus 열거형 테스트")
class PaymentStatusTest {

	@Nested
	@DisplayName("from 메서드 테스트")
	class FromTest {

		@ParameterizedTest
		@ValueSource(strings = {"READY", "ready", "Ready"})
		@DisplayName("READY 상태 변환 성공")
		void READY_상태_변환(String statusName) {
			// when
			PaymentStatus status = PaymentStatus.from(statusName);

			// then
			assertThat(status).isEqualTo(PaymentStatus.READY);
		}

		@ParameterizedTest
		@ValueSource(strings = {"IN_PROGRESS", "in_progress", "In_Progress"})
		@DisplayName("IN_PROGRESS 상태 변환 성공")
		void IN_PROGRESS_상태_변환(String statusName) {
			// when
			PaymentStatus status = PaymentStatus.from(statusName);

			// then
			assertThat(status).isEqualTo(PaymentStatus.IN_PROGRESS);
		}

		@Test
		@DisplayName("WAITING_FOR_DEPOSIT 상태 변환 성공")
		void WAITING_FOR_DEPOSIT_상태_변환() {
			// when
			PaymentStatus status = PaymentStatus.from("WAITING_FOR_DEPOSIT");

			// then
			assertThat(status).isEqualTo(PaymentStatus.WAITING_FOR_DEPOSIT);
		}

		@Test
		@DisplayName("DONE 상태 변환 성공")
		void DONE_상태_변환() {
			// when
			PaymentStatus status = PaymentStatus.from("DONE");

			// then
			assertThat(status).isEqualTo(PaymentStatus.DONE);
		}

		@Test
		@DisplayName("CANCELED 상태 변환 성공")
		void CANCELED_상태_변환() {
			// when
			PaymentStatus status = PaymentStatus.from("CANCELED");

			// then
			assertThat(status).isEqualTo(PaymentStatus.CANCELED);
		}

		@Test
		@DisplayName("PARTIAL_CANCELED 상태 변환 성공")
		void PARTIAL_CANCELED_상태_변환() {
			// when
			PaymentStatus status = PaymentStatus.from("PARTIAL_CANCELED");

			// then
			assertThat(status).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
		}

		@Test
		@DisplayName("ABORTED 상태 변환 성공")
		void ABORTED_상태_변환() {
			// when
			PaymentStatus status = PaymentStatus.from("ABORTED");

			// then
			assertThat(status).isEqualTo(PaymentStatus.ABORTED);
		}

		@Test
		@DisplayName("EXPIRED 상태 변환 성공")
		void EXPIRED_상태_변환() {
			// when
			PaymentStatus status = PaymentStatus.from("EXPIRED");

			// then
			assertThat(status).isEqualTo(PaymentStatus.EXPIRED);
		}

		@Test
		@DisplayName("null 입력 시 IllegalArgumentException이 발생한다")
		void null_입력_예외() {
			// when & then
			assertThatThrownBy(() -> PaymentStatus.from(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("결제 상태 값이 존재해야 합니다.");
		}

		@Test
		@DisplayName("알 수 없는 상태명 입력 시 IllegalArgumentException이 발생한다")
		void 알수없는_상태_예외() {
			// when & then
			assertThatThrownBy(() -> PaymentStatus.from("INVALID_STATUS"))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("빈 문자열 입력 시 IllegalArgumentException이 발생한다")
		void 빈문자열_예외() {
			// when & then
			assertThatThrownBy(() -> PaymentStatus.from(""))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
