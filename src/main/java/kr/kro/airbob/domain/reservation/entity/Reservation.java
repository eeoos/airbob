package kr.kro.airbob.domain.reservation.entity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

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
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.member.Member;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationStatusException;
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
public class Reservation extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

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

	@Column(nullable = false)
	private Integer guestCount;

	@Column(nullable = false)
	private Integer totalPrice;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReservationStatus status;

	private String message;

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
			.build();
	}

	private static int calculatePrice(int basePrice, LocalDateTime checkIn, LocalDateTime checkOut) {
		long nights = ChronoUnit.DAYS.between(checkIn.toLocalDate(), checkOut.toLocalDate());

		if (nights <= 0) {
			return 0;
		}
		return (int) (basePrice * nights);
	}

	public void confirm() {
		if (this.status != ReservationStatus.PAYMENT_PENDING) {
			throw new InvalidReservationStatusException();
		}
		this.status = ReservationStatus.COMPLETED;
	}
}
