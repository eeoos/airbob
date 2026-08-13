package kr.kro.airbob.domain.reservation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

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
		ReservationRequest.Create request = new ReservationRequest.Create(
			1L,
			LocalDate.of(2000, 1, 1),
			LocalDate.of(2000, 1, 2),
			2
		);

		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	@DisplayName("필수 날짜와 양수 숙소 ID 및 인원수 제약은 유지한다")
	void keepsRequiredAndPositiveConstraints() {
		ReservationRequest.Create request = new ReservationRequest.Create(
			0L,
			null,
			null,
			0
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
}
