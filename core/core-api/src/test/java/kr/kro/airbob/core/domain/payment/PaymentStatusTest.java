package kr.kro.airbob.core.domain.payment;

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
		void convertsReady(String statusName) {
			// when
			PaymentStatus status = PaymentStatus.from(statusName);

			// then
			assertThat(status).isEqualTo(PaymentStatus.READY);
		}

		@ParameterizedTest
		@ValueSource(strings = {"IN_PROGRESS", "in_progress", "In_Progress"})
		@DisplayName("IN_PROGRESS 상태 변환 성공")
		void convertsInProgress(String statusName) {
			// when
			PaymentStatus status = PaymentStatus.from(statusName);

			// then
			assertThat(status).isEqualTo(PaymentStatus.IN_PROGRESS);
		}

		@Test
		@DisplayName("WAITING_FOR_DEPOSIT 상태 변환 성공")
		void convertsWaitingForDeposit() {
			// when
			PaymentStatus status = PaymentStatus.from("WAITING_FOR_DEPOSIT");

			// then
			assertThat(status).isEqualTo(PaymentStatus.WAITING_FOR_DEPOSIT);
		}

		@Test
		@DisplayName("DONE 상태 변환 성공")
		void convertsDone() {
			// when
			PaymentStatus status = PaymentStatus.from("DONE");

			// then
			assertThat(status).isEqualTo(PaymentStatus.DONE);
		}

		@Test
		@DisplayName("CANCELED 상태 변환 성공")
		void convertsCanceled() {
			// when
			PaymentStatus status = PaymentStatus.from("CANCELED");

			// then
			assertThat(status).isEqualTo(PaymentStatus.CANCELED);
		}

		@Test
		@DisplayName("PARTIAL_CANCELED 상태 변환 성공")
		void convertsPartialCanceled() {
			// when
			PaymentStatus status = PaymentStatus.from("PARTIAL_CANCELED");

			// then
			assertThat(status).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
		}

		@Test
		@DisplayName("ABORTED 상태 변환 성공")
		void convertsAborted() {
			// when
			PaymentStatus status = PaymentStatus.from("ABORTED");

			// then
			assertThat(status).isEqualTo(PaymentStatus.ABORTED);
		}

		@Test
		@DisplayName("EXPIRED 상태 변환 성공")
		void convertsExpired() {
			// when
			PaymentStatus status = PaymentStatus.from("EXPIRED");

			// then
			assertThat(status).isEqualTo(PaymentStatus.EXPIRED);
		}

		@Test
		@DisplayName("null 입력 시 IllegalArgumentException이 발생한다")
		void rejectsNull() {
			// when & then
			assertThatThrownBy(() -> PaymentStatus.from(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("결제 상태 값이 존재해야 합니다.");
		}

		@Test
		@DisplayName("알 수 없는 상태명 입력 시 IllegalArgumentException이 발생한다")
		void rejectsUnknownStatus() {
			// when & then
			assertThatThrownBy(() -> PaymentStatus.from("INVALID_STATUS"))
				.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("빈 문자열 입력 시 IllegalArgumentException이 발생한다")
		void rejectsEmptyString() {
			// when & then
			assertThatThrownBy(() -> PaymentStatus.from(""))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
