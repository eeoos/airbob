package kr.kro.airbob.domain.reservation.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationRequest {

	public record Quote(
		@NotNull
		@Positive
		Long accommodationId,

		@NotNull
		LocalDate checkInDate,

		@NotNull
		LocalDate checkOutDate,

		@NotNull
		@Positive
		Integer guestCount,

		@Positive
		Long couponId
	) {
	}

	public record Checkout(
		@NotNull
		UUID quoteUid,

		@Size(max = 255)
		String requestMessage
	) {
	}
}
