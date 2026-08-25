package kr.kro.airbob.domain.reservation.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.policy.ReservationQuotePolicy;
import kr.kro.airbob.domain.reservation.policy.ReservationStayPricePolicy;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "reservation_quote")
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationQuote extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(nullable = false, unique = true, updatable = false, columnDefinition = "BINARY(16)")
	private UUID quoteUid;

	@Column(nullable = false, updatable = false)
	private Long memberId;

	@Column(nullable = false, updatable = false)
	private Long accommodationId;

	@Column(nullable = false, updatable = false)
	private String orderName;

	@Column(nullable = false, updatable = false)
	private LocalDate checkInDate;

	@Column(nullable = false, updatable = false)
	private LocalDate checkOutDate;

	@Column(nullable = false, updatable = false)
	private Integer guestCount;

	@Column(updatable = false)
	private Long couponId;

	@Column(nullable = false, updatable = false)
	private Long nightlyPrice;

	@Column(nullable = false, updatable = false)
	private Long nights;

	@Column(nullable = false, updatable = false)
	private Long subtotal;

	@Column(nullable = false, updatable = false)
	private Long discountAmount;

	@Column(nullable = false, updatable = false)
	private Long amount;

	@Column(nullable = false, length = 3, updatable = false)
	private String currency;

	@Column(nullable = false, updatable = false)
	private Instant quotedAt;

	@Column(nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(unique = true)
	private Long reservationId;

	private Instant checkedOutAt;

	@PrePersist
	protected void onCreate() {
		if (quoteUid == null) {
			quoteUid = UUID.randomUUID();
		}
	}

	public static ReservationQuote create(
		Long memberId,
		ReservationRequest.Quote request,
		String orderName,
		String currency,
		ReservationStayPricePolicy.StayPrice stayPrice,
		long discountAmount,
		Instant quotedAt,
		ReservationQuotePolicy quotePolicy
	) {
		long amount = Math.subtractExact(stayPrice.subtotal(), discountAmount);
		if (discountAmount < 0 || amount < 0) {
			throw new IllegalArgumentException("견적 할인액은 숙박 소계를 벗어날 수 없습니다.");
		}

		return ReservationQuote.builder()
			.quoteUid(UUID.randomUUID())
			.memberId(memberId)
			.accommodationId(request.accommodationId())
			.orderName(orderName)
			.checkInDate(request.checkInDate())
			.checkOutDate(request.checkOutDate())
			.guestCount(request.guestCount())
			.couponId(request.couponId())
			.nightlyPrice(stayPrice.nightlyPrice())
			.nights(stayPrice.nights())
			.subtotal(stayPrice.subtotal())
			.discountAmount(discountAmount)
			.amount(amount)
			.currency(currency)
			.quotedAt(quotedAt)
			.expiresAt(quotePolicy.expiresAtFrom(quotedAt))
			.build();
	}

	public boolean isExpiredAt(Instant now) {
		return !expiresAt.isAfter(now);
	}

	public boolean matchesPricing(
		ReservationStayPricePolicy.StayPrice stayPrice,
		long currentDiscountAmount
	) {
		return stayPrice != null
			&& Objects.equals(nightlyPrice, stayPrice.nightlyPrice())
			&& Objects.equals(nights, stayPrice.nights())
			&& Objects.equals(subtotal, stayPrice.subtotal())
			&& Objects.equals(discountAmount, currentDiscountAmount);
	}

	public void attachReservation(Long reservationId, Instant checkedOutAt) {
		if (this.reservationId != null && !Objects.equals(this.reservationId, reservationId)) {
			throw new IllegalStateException("견적에는 하나의 예약만 연결할 수 있습니다.");
		}
		this.reservationId = Objects.requireNonNull(reservationId);
		if (this.checkedOutAt == null) {
			this.checkedOutAt = Objects.requireNonNull(checkedOutAt);
		}
	}

	public boolean isCheckedOut() {
		return reservationId != null;
	}
}
