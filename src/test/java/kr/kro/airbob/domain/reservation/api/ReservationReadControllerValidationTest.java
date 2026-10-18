package kr.kro.airbob.domain.reservation.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.service.PaymentCancellationCommandService;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationHoldPolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestStore;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.domain.reservation.service.ReservationTransactionService;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@ExtendWith(MockitoExtension.class)
class ReservationReadControllerValidationTest {

	@Mock AccommodationSearchRefreshPublisher searchRefreshPublisher;
	@Mock CursorPageInfoCreator cursorPageInfoCreator;
	@Mock MemberRepository memberRepository;
	@Mock ReviewRepository reviewRepository;
	@Mock ReservationRepository reservationRepository;
	@Mock AccommodationRepository accommodationRepository;
	@Mock ReservationHistoryRepository historyRepository;
	@Mock CouponUsageService couponUsageService;
	@Mock BookingWindowProvider bookingWindowProvider;
	@Mock ReservationHoldPolicy holdPolicy;
	@Mock ReservationQuoteRepository quoteRepository;
	@Mock ReservationCheckoutRequestStore checkoutRequestStore;
	@Mock ReservationInventoryService inventoryService;
	@Mock Clock clock;
	@Mock PaymentCancellationCommandService cancellationService;
	@InjectMocks ReservationTransactionService transactionService;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(10L));
		var service = new ReservationService(transactionService, cancellationService, clock);
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
		verifyNoInteractions(reservationRepository, reviewRepository);
	}

	@ParameterizedTest
	@ValueSource(strings = {"guest", "host"})
	void missingOrInaccessibleReservationReturnsNotFound(String role) throws Exception {
		mvc.perform(get("/api/v1/profile/{role}/reservations/{uid}", role,
				"00000000-0000-0000-0000-000000000020"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("R001"));
		verifyNoInteractions(reviewRepository);
	}
}
