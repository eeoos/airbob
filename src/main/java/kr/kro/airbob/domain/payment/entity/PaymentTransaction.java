package kr.kro.airbob.domain.payment.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.service.gateway.ConfirmedPayment;
import kr.kro.airbob.domain.payment.service.gateway.CancelledPayment;
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
	private static final int PAYMENT_KEY_MAX_LENGTH = 200;
	private static final int FAILURE_CODE_MAX_LENGTH = 100;
	private static final int FAILURE_MESSAGE_MAX_LENGTH = 512;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long reservationId;

	private Long paymentId; // 결제 확정 전(시도/실패/가상계좌)에는 null

	@Column(unique = true)
	private Long paymentOperationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PaymentTransactionType transactionType;

	@Enumerated(EnumType.STRING)
	private PaymentStatus status; // 그 시점 PG 상태

	private Long amount;
	@Column(length = PAYMENT_KEY_MAX_LENGTH)
	private String paymentKey;
	private String orderId;
	@Enumerated(EnumType.STRING)
	private PaymentMethod method;

	// 실패 정보
	@Column(length = FAILURE_CODE_MAX_LENGTH)
	private String failureCode;
	@Column(length = FAILURE_MESSAGE_MAX_LENGTH)
	private String failureMessage;

	// 가상계좌 정보
	private String virtualBankCode;
	private String virtualAccountNumber;
	private String virtualCustomerName;
	private Instant virtualDueDate;

	// 취소 정보
	private Long cancelAmount;
	@Column(length = PaymentOperation.CANCELLATION_REASON_MAX_LENGTH)
	private String cancelReason;
	@Column(length = 64)
	private String transactionKey;
	private Instant canceledAt; // PG가 알려준 취소 시각

	public static PaymentTransaction confirm(
		ConfirmedPayment confirmed,
		Reservation reservation,
		Payment payment,
		Long paymentOperationId
	) {
		ConfirmedPayment.VirtualAccountDetails virtualAccount = confirmed.virtualAccount();
		return PaymentTransaction.builder()
			.reservationId(reservation.getId())
			.paymentId(payment.getId())
			.paymentOperationId(paymentOperationId)
			.transactionType(PaymentTransactionType.CONFIRM)
			.status(confirmed.status())
			.amount(confirmed.totalAmount())
			.paymentKey(limitLength(confirmed.paymentKey(), PAYMENT_KEY_MAX_LENGTH))
			.orderId(confirmed.orderId())
			.method(confirmed.method())
			.virtualBankCode(virtualAccount != null ? virtualAccount.bankCode() : null)
			.virtualAccountNumber(virtualAccount != null ? virtualAccount.accountNumber() : null)
			.virtualCustomerName(virtualAccount != null ? virtualAccount.customerName() : null)
			.virtualDueDate(virtualAccount != null ? virtualAccount.dueDate() : null)
			.build();
	}

	public static PaymentTransaction fail(
		PaymentOperation operation,
		Reservation reservation,
		String failureCode,
		String failureMessage
	) {
		return PaymentTransaction.builder()
			.reservationId(reservation.getId())
			.paymentOperationId(operation.getId())
			.transactionType(PaymentTransactionType.FAIL)
			.status(PaymentStatus.ABORTED)
			.amount(operation.getExpectedAmount())
			.paymentKey(limitLength(operation.getPaymentKey(), PAYMENT_KEY_MAX_LENGTH))
			.orderId(reservation.getReservationUid().toString())
			.method(PaymentMethod.UNKNOWN)
			.failureCode(limitLength(failureCode, FAILURE_CODE_MAX_LENGTH))
			.failureMessage(limitLength(failureMessage, FAILURE_MESSAGE_MAX_LENGTH))
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
				? virtualAccount.getDueDate().toInstant() : null)
			.build();
	}

	public static PaymentTransaction cancel(
		CancelledPayment cancelled,
		Reservation reservation,
		Payment payment,
		Long paymentOperationId
	) {
		return PaymentTransaction.builder()
			.reservationId(reservation.getId())
			.paymentId(payment.getId())
			.paymentOperationId(paymentOperationId)
			.transactionType(PaymentTransactionType.CANCEL)
			.status(cancelled.status())
			.amount(cancelled.cancelAmount())
			.paymentKey(limitLength(cancelled.paymentKey(), PAYMENT_KEY_MAX_LENGTH))
			.orderId(cancelled.orderId())
			.method(payment.getMethod())
			.cancelAmount(cancelled.cancelAmount())
			.cancelReason(cancelled.cancelReason())
			.transactionKey(cancelled.transactionKey())
			.canceledAt(cancelled.cancelledAt())
			.build();
	}

	public static PaymentTransaction cancellationFailed(
		PaymentOperation operation,
		Reservation reservation,
		Payment payment,
		String failureCode,
		String failureMessage
	) {
		return PaymentTransaction.builder()
			.reservationId(reservation.getId())
			.paymentId(payment.getId())
			.paymentOperationId(operation.getId())
			.transactionType(PaymentTransactionType.CANCEL_FAIL)
			.status(payment.getStatus())
			.amount(operation.getExpectedAmount())
			.paymentKey(limitLength(payment.getPaymentKey(), PAYMENT_KEY_MAX_LENGTH))
			.orderId(payment.getOrderId())
			.method(payment.getMethod())
			.cancelAmount(operation.getExpectedAmount())
			.cancelReason(operation.getCancellationReason())
			.failureCode(limitLength(failureCode, FAILURE_CODE_MAX_LENGTH))
			.failureMessage(limitLength(failureMessage, FAILURE_MESSAGE_MAX_LENGTH))
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

	private static String limitLength(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}
}
