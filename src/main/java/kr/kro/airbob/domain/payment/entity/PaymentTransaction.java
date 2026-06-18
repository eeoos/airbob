package kr.kro.airbob.domain.payment.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 결제 거래 원장(append-only). 기존 PaymentAttempt(시도/실패/가상계좌) + PaymentCancel(취소)을 단일 이벤트 로그로 통합.
// Payment(현재 상태 애그리거트)는 별도 유지. 원본 FK 없음(이벤트 로그 독립), reservation_id는 항상, payment_id는 확정 후.
@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long reservationId;

	private Long paymentId; // 결제 확정 전(시도/실패/가상계좌)에는 null

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentTransactionType transactionType;

	@Enumerated(EnumType.STRING)
	private PaymentStatus status; // 그 시점 PG 상태

	private Long amount;
	private String paymentKey;
	private String orderId;
	@Enumerated(EnumType.STRING)
	private PaymentMethod method;

	// 실패 정보
	private String failureCode;
	@Column(length = 512)
	private String failureMessage;

	// 가상계좌 정보
	private String virtualBankCode;
	private String virtualAccountNumber;
	private String virtualCustomerName;
	private LocalDateTime virtualDueDate;

	// 취소 정보
	private Long cancelAmount;
	@Column(length = 200)
	private String cancelReason;
	private String transactionKey;
	private LocalDateTime canceledAt; // PG가 알려준 취소 시각

	// 결제 승인 성공 (Payment 생성 직후, payment_id 연결)
	public static PaymentTransaction confirm(TossPaymentResponse response, Reservation reservation, Payment payment) {
		TossPaymentResponse.VirtualAccount virtualAccount = response.getVirtualAccount();
		return baseFromResponse(response, reservation)
			.paymentId(payment.getId())
			.transactionType(PaymentTransactionType.CONFIRM)
			.virtualBankCode(virtualAccount != null ? virtualAccount.getBankCode() : null)
			.virtualAccountNumber(virtualAccount != null ? virtualAccount.getAccountNumber() : null)
			.virtualCustomerName(virtualAccount != null ? virtualAccount.getCustomerName() : null)
			.virtualDueDate(virtualAccount != null && virtualAccount.getDueDate() != null
				? virtualAccount.getDueDate().toLocalDateTime() : null)
			.build();
	}

	// 결제 승인 실패
	public static PaymentTransaction fail(PaymentRequest.Confirm event, Reservation reservation,
		String failureCode, String failureMessage) {
		return PaymentTransaction.builder()
			.reservationId(reservation.getId())
			.transactionType(PaymentTransactionType.FAIL)
			.status(PaymentStatus.ABORTED)
			.amount(Long.valueOf(event.amount()))
			.paymentKey(event.paymentKey())
			.orderId(event.orderId())
			.method(PaymentMethod.UNKNOWN)
			.failureCode(failureCode)
			.failureMessage(failureMessage)
			.build();
	}

	// 가상계좌 발급 (Payment 생성 전)
	public static PaymentTransaction virtualIssued(TossPaymentResponse response, Reservation reservation) {
		TossPaymentResponse.VirtualAccount virtualAccount = response.getVirtualAccount();
		return baseFromResponse(response, reservation)
			.transactionType(PaymentTransactionType.VIRTUAL_ISSUED)
			.virtualBankCode(virtualAccount != null ? virtualAccount.getBankCode() : null)
			.virtualAccountNumber(virtualAccount != null ? virtualAccount.getAccountNumber() : null)
			.virtualCustomerName(virtualAccount != null ? virtualAccount.getCustomerName() : null)
			.virtualDueDate(virtualAccount != null && virtualAccount.getDueDate() != null
				? virtualAccount.getDueDate().toLocalDateTime() : null)
			.build();
	}

	// 취소(전체/부분). 갱신된 Payment 상태로 type 결정.
	public static PaymentTransaction cancel(TossPaymentResponse.Cancel cancelData, Payment payment) {
		PaymentTransactionType type = payment.getStatus() == PaymentStatus.PARTIAL_CANCELED
			? PaymentTransactionType.PARTIAL_CANCEL : PaymentTransactionType.CANCEL;
		return PaymentTransaction.builder()
			.reservationId(payment.getReservation().getId())
			.paymentId(payment.getId())
			.transactionType(type)
			.status(payment.getStatus())
			.amount(cancelData.getCancelAmount())
			.paymentKey(payment.getPaymentKey())
			.orderId(payment.getOrderId())
			.method(payment.getMethod())
			.cancelAmount(cancelData.getCancelAmount())
			.cancelReason(cancelData.getCancelReason())
			.transactionKey(cancelData.getTransactionKey())
			.canceledAt(cancelData.getCanceledAt() != null ? cancelData.getCanceledAt().toLocalDateTime() : null)
			.build();
	}

	private static PaymentTransactionBuilder<?, ?> baseFromResponse(TossPaymentResponse response, Reservation reservation) {
		TossPaymentResponse.Failure failure = response.getFailure();
		return PaymentTransaction.builder()
			.reservationId(reservation.getId())
			.status(PaymentStatus.from(response.getStatus()))
			.amount(response.getTotalAmount())
			.paymentKey(response.getPaymentKey())
			.orderId(response.getOrderId())
			.method(PaymentMethod.fromDescription(response.getMethod()))
			.failureCode(failure != null ? failure.getCode() : null)
			.failureMessage(failure != null ? failure.getMessage() : null);
	}
}
