package kr.kro.airbob.domain.reservation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

@DisplayName("예약 생성 요청 검증 테스트")
class ReservationRequestValidationTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("날짜의 과거 여부는 DTO 검증 대상이 아니다")
	void allowsPastDatesForAccommodationLocalDateValidation() {
		ReservationRequest.Quote request = new ReservationRequest.Quote(
			1L,
			LocalDate.of(2000, 1, 1),
			LocalDate.of(2000, 1, 2),
			2,
			null
		);

		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	@DisplayName("필수 날짜와 양수 숙소 ID 및 인원수 제약은 유지한다")
	void keepsRequiredAndPositiveConstraints() {
		ReservationRequest.Quote request = new ReservationRequest.Quote(
			0L,
			null,
			null,
			0,
			null
		);

		assertThat(validator.validate(request))
			.extracting(violation -> violation.getPropertyPath().toString())
			.containsExactlyInAnyOrder(
				"accommodationId",
				"checkInDate",
				"checkOutDate",
				"guestCount"
			);
	}

	@Test
	@DisplayName("요청사항은 선택 입력이며 255자까지 허용한다")
	void allowsOptionalRequestMessageUpTo255Characters() {
		ReservationRequest.Checkout withoutMessage = new ReservationRequest.Checkout(
			UUID.randomUUID(), null);
		ReservationRequest.Checkout maxLengthMessage = new ReservationRequest.Checkout(
			UUID.randomUUID(), "a".repeat(255));

		assertThat(validator.validate(withoutMessage)).isEmpty();
		assertThat(validator.validate(maxLengthMessage)).isEmpty();
	}

	@Test
	@DisplayName("요청사항이 255자를 초과하면 거부한다")
	void rejectsRequestMessageOver255Characters() {
		ReservationRequest.Checkout request = new ReservationRequest.Checkout(
			UUID.randomUUID(), "a".repeat(256));

		assertThat(validator.validate(request))
			.extracting(violation -> violation.getPropertyPath().toString())
			.containsExactly("requestMessage");
	}
}
