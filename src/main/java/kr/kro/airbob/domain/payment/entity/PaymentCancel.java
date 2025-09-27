package kr.kro.airbob.domain.payment.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancel extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "payment_id", nullable = false)
	private Payment payment;

	@Column(nullable = false)
	private Long cancelAmount;

	@Column(length = 200)
	private String cancelReason;

	@Column(nullable = false)
	private String transactionKey;

	@Column(nullable = false)
	private LocalDateTime canceledAt;

	public void assignPayment(Payment payment) {
		this.payment = payment;
	}

	public static PaymentCancel create(TossPaymentResponse.Cancel cancelData, Payment payment) {
		return PaymentCancel.builder()
			.cancelAmount(cancelData.getCancelAmount())
			.cancelReason(cancelData.getCancelReason())
			.transactionKey(cancelData.getTransactionKey())
			.canceledAt(cancelData.getCanceledAt())
			.payment(payment)
			.build();
	}

}
