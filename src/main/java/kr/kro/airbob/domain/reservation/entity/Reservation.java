package kr.kro.airbob.domain.reservation.entity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
import kr.kro.airbob.common.domain.UpdatableEntity;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
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
public class Reservation extends UpdatableEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@JdbcTypeCode(SqlTypes.BINARY)
	@Column(nullable = false, unique = true, columnDefinition = "BINARY(16)")
	private UUID reservationUid;

	@Column(length = 10, unique = true)
	private String confirmationCode;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "accommodation_id", nullable = false)
	private Accommodation accommodation;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "guest_id", nullable = false)
	private Member guest;

	@Column(nullable = false)
	private LocalDateTime checkIn;

	@Column(nullable = false)
	private LocalDateTime checkOut;

	// todo: 숙박하는 세부 정보를 넣어야 함.
	// 성인, 어린이, 유아, 펫
	@Column(nullable = false)
	private Integer guestCount;

	@Column(nullable = false)
	private Integer totalPrice;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReservationStatus status;

	private String message;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	@PrePersist
	protected void onCreate() {
		if (this.reservationUid == null) {
			this.reservationUid = UUID.randomUUID();
		}
	}

	public static Reservation createPendingReservation(Accommodation accommodation, Member guest,
		ReservationRequest.Create request) {

		LocalDateTime checkInDateTime = request.checkInDate().atTime(accommodation.getCheckInTime());
		LocalDateTime checkOutDateTime = request.checkOutDate().atTime(accommodation.getCheckOutTime());
		int price = calculatePrice(accommodation.getBasePrice(), checkInDateTime, checkOutDateTime);

		return Reservation.builder()
			.accommodation(accommodation)
			.guest(guest)
			.checkIn(checkInDateTime)
			.checkOut(checkOutDateTime)
			.guestCount(request.guestCount())
			.totalPrice(price)
			.status(ReservationStatus.PAYMENT_PENDING)
			.message(request.message())
			.expiresAt(LocalDateTime.now().plusMinutes(15)) // 15분 후 만료
			.build();
	}

	private static int calculatePrice(int basePrice, LocalDateTime checkIn, LocalDateTime checkOut) {
		long nights = ChronoUnit.DAYS.between(checkIn.toLocalDate(), checkOut.toLocalDate());

		if (nights <= 0) {
			throw new InvalidReservationDateException();
		}
		return (int) (basePrice * nights);
	}

	public void confirm(String confirmationCode) {
		if (this.status != ReservationStatus.PAYMENT_PENDING) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_CONFIRM_RESERVATION);
		}
		this.status = ReservationStatus.CONFIRMED;
		this.confirmationCode = confirmationCode;
	}

	public void expire() {
		if (this.status != ReservationStatus.PAYMENT_PENDING) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_EXPIRE_RESERVATION);
		}
		this.status = ReservationStatus.EXPIRED;
	}

	public void cancel() {
		if (this.status != ReservationStatus.CONFIRMED) {
			throw new InvalidReservationStatusException(ErrorCode.CANNOT_CANCEL_RESERVATION);
		}
		this.status = ReservationStatus.CANCELLED;
	}

	public void failCancellation() {
		if (this.status != ReservationStatus.CANCELLED) {
			return;
		}
		this.status = ReservationStatus.CANCELLATION_FAILED;
	}
}
