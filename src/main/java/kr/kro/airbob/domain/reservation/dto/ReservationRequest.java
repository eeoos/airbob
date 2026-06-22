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

		// 적용할 보유 쿠폰 (선택). null 이면 할인 없음.
		Long couponId
		) {

		// 쿠폰 미적용 편의 생성자 (기존 호출부 호환)
		public Create(Long accommodationId, LocalDate checkInDate, LocalDate checkOutDate, Integer guestCount) {
			this(accommodationId, checkInDate, checkOutDate, guestCount, null);
		}
	}
}
