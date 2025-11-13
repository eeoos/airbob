package kr.kro.airbob.domain.payment.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String paymentKey; // pg 결제 키

	@Column(nullable = false)
	private String orderId; // pg 주문 id

	@Column(nullable = false)
	private Long amount; // 시도 금액

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentMethod method;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentStatus status;

	private String failureCode;

	@Column(length = 512)
	private String failureMessage;

	private String virtualBankCode;
	private String virtualAccountNumber;
	private String virtualCustomerName;
	private LocalDateTime virtualDueDate;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reservation_id", nullable = false)
	private Reservation reservation;

	public static PaymentAttempt create(TossPaymentResponse response, Reservation reservation) {
		TossPaymentResponse.Failure failure = response.getFailure();
		TossPaymentResponse.VirtualAccount virtualAccount = response.getVirtualAccount();

		return PaymentAttempt.builder()
			.paymentKey(response.getPaymentKey())
			.orderId(response.getOrderId())
			.amount(response.getTotalAmount())
			.method(PaymentMethod.fromDescription(response.getMethod()))
			.status(PaymentStatus.from(response.getStatus()))
			.failureCode(failure != null ? failure.getCode() : null)
			.failureMessage(failure != null ? failure.getMessage() : null)
			.reservation(reservation)
			.virtualBankCode(virtualAccount != null ? virtualAccount.getBankCode() : null)
			.virtualAccountNumber(virtualAccount != null ? virtualAccount.getAccountNumber() : null)
			.virtualCustomerName(virtualAccount != null ? virtualAccount.getCustomerName() : null)
			.virtualDueDate(virtualAccount != null ? virtualAccount.getDueDate().toLocalDateTime() : null)
			.build();
	}

	public static PaymentAttempt createFailedAttempt(
		String paymentKey,
		String orderId,
		Long amount,
		Reservation reservation,
		String errorCode,
		String errorMessage) {
		return PaymentAttempt.builder()
			.paymentKey(paymentKey)
			.orderId(orderId)
			.amount(amount)
			.method(PaymentMethod.UNKNOWN)
			.status(PaymentStatus.ABORTED)
			.failureCode(errorCode)
			.failureMessage(errorMessage)
			.reservation(reservation)
			.build();
	}
}
