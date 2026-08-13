package kr.kro.airbob.domain.payment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.entity.PaymentTransactionType;

@JsonTest
@DisplayName("결제 응답 시간 테스트")
class PaymentResponseTest {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	@DisplayName("승인 시각과 UTC 감사 시각을 offset이 있는 절대 시각으로 응답한다")
	void paymentInfoUsesInstants() {
		Payment payment = Payment.builder()
			.paymentKey("payment-key")
			.orderId("order-id")
			.amount(100_000L)
			.balanceAmount(100_000L)
			.method(PaymentMethod.CARD)
			.status(PaymentStatus.DONE)
			.approvedAt(Instant.parse("2026-08-12T05:30:00.123456Z"))
			.createdAt(LocalDateTime.of(2026, 8, 12, 5, 29))
			.build();

		PaymentResponse.PaymentInfo response = PaymentResponse.PaymentInfo.from(payment, List.of());

		assertThat(response.requestedAt()).isEqualTo(Instant.parse("2026-08-12T05:29:00Z"));
		assertThat(response.approvedAt()).isEqualTo(Instant.parse("2026-08-12T05:30:00.123456Z"));
	}

	@Test
	@DisplayName("취소 시각과 가상계좌 만료 시각을 절대 시각으로 응답한다")
	void transactionTimesUseInstants() {
		Instant canceledAt = Instant.parse("2026-08-12T06:00:00.123456Z");
		Instant dueDate = Instant.parse("2026-08-13T14:30:00.654321Z");
		PaymentTransaction transaction = PaymentTransaction.builder()
			.transactionType(PaymentTransactionType.CANCEL)
			.cancelAmount(100_000L)
			.cancelReason("사용자 요청")
			.canceledAt(canceledAt)
			.virtualDueDate(dueDate)
			.createdAt(LocalDateTime.of(2026, 8, 12, 5, 59))
			.build();

		PaymentResponse.CancelInfo cancelInfo = PaymentResponse.CancelInfo.from(transaction);
		PaymentResponse.VirtualAccountInfo virtualAccountInfo =
			PaymentResponse.VirtualAccountInfo.from(transaction);

		assertThat(cancelInfo.canceledAt()).isEqualTo(canceledAt);
		assertThat(virtualAccountInfo.dueDate()).isEqualTo(dueDate);
	}

	@Test
	@DisplayName("결제 API의 모든 절대 시각은 ISO-8601 UTC Z 문자열로 직렬화한다")
	void serializesAbsoluteTimesWithUtcOffset() throws Exception {
		PaymentResponse.PaymentInfo response = PaymentResponse.PaymentInfo.builder()
			.orderId("order-id")
			.status(PaymentStatus.DONE)
			.requestedAt(Instant.parse("2026-08-12T05:29:00.123456Z"))
			.approvedAt(Instant.parse("2026-08-12T05:30:00.654321Z"))
			.build();
		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

		assertThat(json.path("requested_at").asText())
			.isEqualTo("2026-08-12T05:29:00.123456Z");
		assertThat(json.path("approved_at").asText())
			.isEqualTo("2026-08-12T05:30:00.654321Z");
	}
}
