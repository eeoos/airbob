package kr.kro.airbob.domain.payment.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // 문서에 명시된 필드 외에는 무시
public class TossPaymentResponse {
	private String paymentKey;
	private String orderId;
	private Long totalAmount;
	private String method;
	private String status;
	private LocalDateTime requestedAt;
	private LocalDateTime approvedAt;
	private Long balanceAmount;
	private Failure failure;

	@Getter
	@Builder
	public static class Failure {
		private String code;
		private String message;
	}
}
