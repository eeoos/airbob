package kr.kro.airbob.domain.accommodation.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;

import java.time.format.DateTimeFormatter;

import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.service.AccommodationCommandService;
import kr.kro.airbob.domain.accommodation.service.AccommodationImageService;
import kr.kro.airbob.domain.accommodation.service.AccommodationQueryService;

@DisplayName("숙소 예약 가능 정보 API 테스트")
class AccommodationAvailabilityApiTest {

	private AccommodationQueryService accommodationQueryService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		accommodationQueryService = mock(AccommodationQueryService.class);
		ObjectMapper objectMapper = new ObjectMapper()
			.registerModule(new JavaTimeModule()
				.addSerializer(LocalDate.class, new LocalDateSerializer(DateTimeFormatter.ISO_LOCAL_DATE)))
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		AccommodationController controller = new AccommodationController(
			mock(AccommodationCommandService.class),
			mock(AccommodationImageService.class),
			accommodationQueryService
		);
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.build();
	}

	@Test
	@DisplayName("숙소 ID의 예약 가능 기간과 예약 불가 구간을 반환한다")
	void returnsAccommodationAvailability() throws Exception {
		when(accommodationQueryService.findAccommodationAvailability(42L))
			.thenReturn(new AccommodationResponse.Availability(
				LocalDate.of(2026, 8, 13),
				LocalDate.of(2026, 11, 13),
				List.of(new AccommodationResponse.UnavailableDateRange(
					LocalDate.of(2026, 8, 20),
					LocalDate.of(2026, 8, 23)
				))
			));

		mockMvc.perform(get("/api/v1/accommodations/42/availability"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.booking_window_start_inclusive").value("2026-08-13"))
			.andExpect(jsonPath("$.data.booking_window_end_exclusive").value("2026-11-13"))
			.andExpect(jsonPath("$.data.unavailable_ranges[0].start_date").value("2026-08-20"))
			.andExpect(jsonPath("$.data.unavailable_ranges[0].end_date_exclusive").value("2026-08-23"));
	}
}
