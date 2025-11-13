package kr.kro.airbob.domain.payment.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import kr.kro.airbob.common.domain.UpdatableEntity;
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
public class Payment extends UpdatableEntity {

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

	@Builder.Default
	@OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<PaymentCancel> cancels = new ArrayList<>();

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

	public void updateOnCancel(TossPaymentResponse response) {
		this.status = PaymentStatus.from(response.getStatus());
		this.balanceAmount = response.getBalanceAmount();

		if (response.getCancels() != null && !response.getCancels().isEmpty()) {
			TossPaymentResponse.Cancel cancelData = response.getCancels().getLast();
			PaymentCancel paymentCancel = PaymentCancel.create(cancelData, this);
			this.addCancel(paymentCancel);
		}
	}

	public void addCancel(PaymentCancel cancel) {
		// 양방향 관계
		this.cancels.add(cancel);
		cancel.assignPayment(this);
	}
}
