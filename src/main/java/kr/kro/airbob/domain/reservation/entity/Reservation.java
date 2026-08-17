package kr.kro.airbob.domain.reservation.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationLocalTimeException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationStatusException;
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
public class Reservation extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(nullable = false, unique = true, columnDefinition = "BINARY(16)")
	private UUID reservationUid;

	@Column(length = 10, unique = true)
	private String reservationCode;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "accommodation_id", nullable = false)
	private Accommodation accommodation;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "guest_id", nullable = false)
	private Member guest;

	@Column(nullable = false)
	private LocalDate checkInDate;

	@Column(nullable = false)
	private LocalDate checkOutDate;

	@Column(nullable = false)
	private Instant checkInAt;

	@Column(nullable = false)
	private Instant checkOutAt;

	@Column(nullable = false, length = 64)
	private String timeZoneId;

	// todo: 숙박하는 세부 정보를 넣어야 함.
	// 성인, 어린이, 유아, 펫
	@Column(nullable = false)
	private Integer guestCount;

	@Column(nullable = false)
	private Long totalPrice;

	// 적용된 쿠폰 할인액 (없으면 0)
	@Builder.Default
	@Column(nullable = false)
	private Long discountAmount = 0L;

	@Column(length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReservationStatus status;

	private String message;

	@Column(nullable = false)
	private Instant expiresAt;

	@PrePersist
	protected void onCreate() {
		if (this.reservationUid == null) {
			this.reservationUid = UUID.randomUUID();
		}
	}

	public static Reservation createPendingReservation(Accommodation accommodation, Member guest,
		ReservationRequest.Create request, String reservationCode, Instant now) {

		ZoneId timeZone = ZoneId.of(accommodation.getTimeZoneId());
		Instant checkInAt = resolveStayInstant(
			request.checkInDate(), accommodation.getCheckInTime(), timeZone);
		Instant checkOutAt = resolveStayInstant(
			request.checkOutDate(), accommodation.getCheckOutTime(), timeZone);
		Long price = calculatePrice(accommodation.getBasePrice(), request.checkInDate(), request.checkOutDate());

		return Reservation.builder()
			.accommodation(accommodation)
			.guest(guest)
			.checkInDate(request.checkInDate())
			.checkOutDate(request.checkOutDate())
			.checkInAt(checkInAt)
			.checkOutAt(checkOutAt)
			.timeZoneId(timeZone.getId())
			.guestCount(request.guestCount())
			.totalPrice(price)
			// todo: 국제화를 고려하지 못하여 KRW로 하드코딩
			// 이후 국제화 도입 필요
			.currency("KRW")
			.status(ReservationStatus.PAYMENT_PENDING)
			// .message(request.message())
			.expiresAt(now.plus(15, ChronoUnit.MINUTES))
			.reservationCode(reservationCode)
			.build();
	}

	private static Instant resolveStayInstant(LocalDate date, java.time.LocalTime time, ZoneId timeZone) {
		LocalDateTime localDateTime = date.atTime(time);
		List<ZoneOffset> validOffsets = timeZone.getRules().getValidOffsets(localDateTime);
		if (validOffsets.isEmpty()) {
			throw new InvalidReservationLocalTimeException();
		}
		return localDateTime.toInstant(validOffsets.getFirst());
	}

	// 쿠폰 할인 적용 — 할인액을 기록하고 결제 금액을 차감
	public void applyDiscount(long discount) {
		if (discount <= 0) {
			return;
		}
		long applied = Math.min(discount, this.totalPrice);
		this.discountAmount = applied;
		this.totalPrice -= applied;
	}

	private static Long calculatePrice(Long basePrice, LocalDate checkIn, LocalDate checkOut) {
		long nights = ChronoUnit.DAYS.between(checkIn, checkOut);

		if (nights <= 0) {
			throw new InvalidReservationDateException();
		}
		return (basePrice * nights);
	}

	public void confirm() {
		if (this.status != ReservationStatus.PAYMENT_PROCESSING) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_CONFIRM_RESERVATION);
		}
		this.status = ReservationStatus.CONFIRMED;
	}

	public void confirmComplimentary() {
		if (this.status != ReservationStatus.PAYMENT_PENDING || requiresPayment()) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_CONFIRM_RESERVATION);
		}
		this.status = ReservationStatus.CONFIRMED;
	}

	public boolean requiresPayment() {
		return !Long.valueOf(0L).equals(this.totalPrice);
	}

	public boolean startPayment(Instant now) {
		if (this.status != ReservationStatus.PAYMENT_PENDING || isExpiredAt(now)) {
			return false;
		}
		this.status = ReservationStatus.PAYMENT_PROCESSING;
		return true;
	}

	public boolean isExpiredAt(Instant now) {
		return !this.expiresAt.isAfter(now);
	}

	public boolean matchesPaymentRequest(String orderId, long amount) {
		return Objects.equals(this.reservationUid.toString(), orderId)
			&& Objects.equals(this.totalPrice, amount);
	}

	public boolean belongsToGuest(Long memberId) {
		return this.guest != null && Objects.equals(this.guest.getId(), memberId);
	}

	public void expire() {
		if (this.status != ReservationStatus.PAYMENT_PENDING
			&& this.status != ReservationStatus.PAYMENT_PROCESSING) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_EXPIRE_RESERVATION);
		}
		this.status = ReservationStatus.EXPIRED;
	}

	public void expireAfterFinalPaymentDecline() {
		if (this.status != ReservationStatus.PAYMENT_PROCESSING) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_EXPIRE_RESERVATION);
		}
		this.status = ReservationStatus.EXPIRED;
	}

	public boolean requestCancellation() {
		if (this.status == ReservationStatus.CANCELLATION_PENDING) {
			return false;
		}
		if (this.status != ReservationStatus.CONFIRMED) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_CANCEL_RESERVATION);
		}
		this.status = ReservationStatus.CANCELLATION_PENDING;
		return true;
	}

	public boolean cancelComplimentary() {
		if (this.status == ReservationStatus.CANCELLED) {
			return false;
		}
		if (this.status != ReservationStatus.CONFIRMED || requiresPayment()) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_CANCEL_RESERVATION);
		}
		this.status = ReservationStatus.CANCELLED;
		return true;
	}

	public boolean completeCancellation() {
		if (this.status == ReservationStatus.CANCELLED) {
			return false;
		}
		if (this.status != ReservationStatus.CANCELLATION_PENDING
			&& this.status != ReservationStatus.CANCELLATION_FAILED) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_CANCEL_RESERVATION);
		}
		this.status = ReservationStatus.CANCELLED;
		return true;
	}

	public boolean failCancellation() {
		if (this.status == ReservationStatus.CANCELLATION_FAILED) {
			return false;
		}
		if (this.status != ReservationStatus.CANCELLATION_PENDING) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_CANCEL_RESERVATION);
		}
		this.status = ReservationStatus.CANCELLATION_FAILED;
		return true;
	}

	public void recoverLegacyCancellationFailure() {
		if (this.status != ReservationStatus.CANCELLED) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_CANCEL_RESERVATION);
		}
		this.status = ReservationStatus.CANCELLATION_FAILED;
	}
}
