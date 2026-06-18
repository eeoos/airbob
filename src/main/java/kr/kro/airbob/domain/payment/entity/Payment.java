package kr.kro.airbob.domain.payment.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
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
public class Payment extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(nullable = false, unique = true, columnDefinition = "BINARY(16)")
	private UUID paymentUid;

	@Column(nullable = false, unique = true)
	private String paymentKey;

	@Column(nullable = false)
	private String orderId;

	@Column(nullable = false)
	private Long amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentMethod method;

	@Column(nullable = false)
	private LocalDateTime approvedAt;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reservation_id", nullable = false)
	private Reservation reservation;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentStatus status;

	@Column(nullable = false)
	private Long balanceAmount; // 잔액

	@PrePersist
	protected void onCreate() {
		if (this.paymentUid == null) {
			this.paymentUid = UUID.randomUUID();
		}
	}

	public static Payment create(TossPaymentResponse response, Reservation reservation) {
		return Payment.builder()
			.paymentKey(response.getPaymentKey())
			.orderId(response.getOrderId())
			.amount(response.getTotalAmount())
			.balanceAmount(response.getTotalAmount())
			.method(PaymentMethod.fromDescription(response.getMethod()))
			.status(PaymentStatus.from(response.getStatus()))
			.approvedAt(response.getApprovedAt().toLocalDateTime())
			.reservation(reservation)
			.build();
	}

	// 취소 시 현재 상태(상태/잔액)만 갱신. 취소 "사건"은 PaymentTransaction(CANCEL/PARTIAL_CANCEL)으로 서비스가 기록한다.
	public void updateOnCancel(TossPaymentResponse response) {
		this.status = PaymentStatus.from(response.getStatus());
		this.balanceAmount = response.getBalanceAmount();
	}
}
