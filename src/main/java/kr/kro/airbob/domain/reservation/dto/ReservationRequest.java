package kr.kro.airbob.domain.reservation.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationRequest {

	public record Create(
		@NotNull
		@Positive
		Long accommodationId,

		@NotNull
		@FutureOrPresent(message = "체크인 날짜는 오늘 포함 이후여야 합니다.")
		LocalDate checkInDate,

		@NotNull
		@Future(message = "체크아웃 날짜는 오늘 이후여야 합니다.")
		LocalDate checkOutDate,

		@NotNull
		@Positive
		Integer guestCount,

		String message
		) {
	}
}
