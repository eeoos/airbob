package kr.kro.airbob.core.api.controller.v1.reservation;

import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import kr.kro.airbob.core.api.controller.GlobalExceptionHandler;
import kr.kro.airbob.core.api.security.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.core.domain.reservation.ReservationQueryService;
import kr.kro.airbob.core.domain.reservation.ReservationReader;
import kr.kro.airbob.core.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.core.domain.review.ReviewReader;
import kr.kro.airbob.core.domain.review.repository.ReviewRepository;
import kr.kro.airbob.core.support.context.UserContext;
import kr.kro.airbob.core.support.context.UserInfo;
import kr.kro.airbob.core.support.cursor.util.CursorPageInfoCreator;

@ExtendWith(MockitoExtension.class)
class ReservationReadControllerValidationTest {

	@Mock
	CursorPageInfoCreator cursorPageInfoCreator;
	@Mock
	ReviewRepository reviewRepository;
	@Mock
	ReservationRepository reservationRepository;
	@Mock
	Clock clock;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(10L));
		var service = new ReservationQueryService(new ReservationReader(reservationRepository),
			new ReviewReader(reviewRepository), cursorPageInfoCreator, clock);
		mvc = MockMvcBuilders.standaloneSetup(
			new ReservationGuestController(service), new ReservationHostController(service))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
			.build();
	}

	@AfterEach
	void clearContext() {
		UserContext.clear();
	}

	@ParameterizedTest
	@ValueSource(strings = {"guest", "host"})
	void malformedUidReturnsNotFoundBeforeQuerying(String role) throws Exception {
		mvc.perform(get("/api/v1/profile/{role}/reservations/not-a-uuid", role))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("R001"));
		then(reservationRepository).shouldHaveNoInteractions();
		then(reviewRepository).shouldHaveNoInteractions();
	}

	@ParameterizedTest
	@ValueSource(strings = {"guest", "host"})
	void missingOrInaccessibleReservationReturnsNotFound(String role) throws Exception {
		mvc.perform(get("/api/v1/profile/{role}/reservations/{uid}", role,
			"00000000-0000-0000-0000-000000000020"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("R001"));
		then(reviewRepository).shouldHaveNoInteractions();
	}
}
