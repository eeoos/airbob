package kr.kro.airbob.search.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

@DisplayName("숙소 검색 요청 검증 테스트")
class AccommodationSearchRequestValidationTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("날짜의 과거 여부는 DTO가 아니라 숙소 현지 예약 창으로 검증한다")
	void allowsPastDatesForAccommodationLocalDateValidation() {
		AccommodationSearchRequest.AccommodationSearchRequestDto request = requestWithDates(
			LocalDate.of(2000, 1, 1),
			LocalDate.of(2000, 1, 2)
		);

		assertThat(validator.validate(request)).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("invalidDateRanges")
	@DisplayName("체크인과 체크아웃은 함께 입력하고 체크아웃이 더 뒤여야 한다")
	void rejectsNonIncreasingDateRanges(LocalDate checkIn, LocalDate checkOut) {
		AccommodationSearchRequest.AccommodationSearchRequestDto request = requestWithDates(checkIn, checkOut);

		assertThat(validator.validate(request))
			.extracting(violation -> violation.getPropertyPath().toString())
			.containsExactly("validDateRange");
	}

	private static Stream<Arguments> invalidDateRanges() {
		return Stream.of(
			Arguments.of(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 12)),
			Arguments.of(LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 12)),
			Arguments.of(LocalDate.of(2026, 8, 13), null),
			Arguments.of(null, LocalDate.of(2026, 8, 14))
		);
	}

	@Test
	@DisplayName("체크인과 체크아웃을 모두 생략하면 날짜 조건 없는 검색으로 허용한다")
	void allowsBothDatesToBeOmitted() {
		AccommodationSearchRequest.AccommodationSearchRequestDto request = requestWithDates(null, null);

		assertThat(validator.validate(request)).isEmpty();
	}

	private AccommodationSearchRequest.AccommodationSearchRequestDto requestWithDates(
		LocalDate checkIn,
		LocalDate checkOut
	) {
		AccommodationSearchRequest.AccommodationSearchRequestDto request =
			new AccommodationSearchRequest.AccommodationSearchRequestDto();
		request.setCheckIn(checkIn);
		request.setCheckOut(checkOut);
		return request;
	}
}
