package kr.kro.airbob.domain.reservation.dto;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationRequest {

	@Schema(name = "ReservationRequestQuote")
	public record Quote(
		@NotNull
		@Positive
		@Schema(description = "게시된 숙소 ID", example = "101", minimum = "1")
		Long accommodationId,

		@NotNull
		@Schema(description = "숙소 현지 날짜의 체크인일", example = "2027-01-10")
		LocalDate checkInDate,

		@NotNull
		@Schema(description = "숙소 현지 날짜의 체크아웃일. 해당 밤은 숙박에 포함하지 않습니다.", example = "2027-01-12")
		LocalDate checkOutDate,

		@NotNull
		@Positive
		@Schema(description = "예약 인원. 숙소의 최대 수용 인원을 넘을 수 없습니다.", example = "2", minimum = "1")
		Integer guestCount,

		@Positive
		@Schema(description = "보유 쿠폰의 캠페인 ID. 미사용 시 생략하거나 null입니다. 회원 쿠폰 발급 내역 ID가 아닙니다.",
			example = "7", minimum = "1", nullable = true)
		Long couponId
	) {
	}

	@Schema(name = "ReservationRequestCheckout")
	public record Checkout(
		@NotNull
		@Schema(description = "견적 생성 응답의 quote_uid", example = "00000000-0000-0000-0000-000000000010")
		UUID quoteUid,

		@Size(max = 255)
		@Schema(description = "호스트에게 전달할 최대 255자 요청. 생략과 null은 같으며 빈 문자열·공백은 그대로 멱등성 비교에 포함됩니다.",
			example = "늦은 체크인 예정입니다.", nullable = true)
		String requestMessage
	) {
	}
}
