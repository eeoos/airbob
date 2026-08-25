package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.ReservationQuote;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationLocalTimeException;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationQuotePolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationQuoteRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("예약 견적 서비스 테스트")
class ReservationQuoteServiceTest {

	private static final long MEMBER_ID = 7L;
	private static final long ACCOMMODATION_ID = 31L;
	private static final long COUPON_ID = 55L;
	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

	@Mock private AccommodationRepository accommodationRepository;
	@Mock private MemberRepository memberRepository;
	@Mock private ReservationInventoryService inventoryService;
	@Mock private ReservationQuoteRepository quoteRepository;
	@Mock private CouponUsageService couponUsageService;
	@Mock private BookingWindowProvider bookingWindowProvider;
	@Mock private Clock clock;
	@Spy private ReservationQuotePolicy quotePolicy = ReservationQuotePolicy.defaultPolicy();

	@InjectMocks private ReservationQuoteService quoteService;

	@Captor private ArgumentCaptor<ReservationQuote> quoteCaptor;

	private Accommodation accommodation;

	@BeforeEach
	void setUp() {
		Member host = Member.builder().id(3L).build();
		Member guest = Member.builder().id(MEMBER_ID).build();
		accommodation = Accommodation.builder()
			.id(ACCOMMODATION_ID)
			.accommodationUid(UUID.fromString("be72e94f-1fa0-4ed4-a071-56051dc89c7e"))
			.name("한강 전망 숙소")
			.basePrice(120_000L)
			.currency("KRW")
			.timeZoneId("Asia/Seoul")
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(4).build())
			.status(AccommodationStatus.PUBLISHED)
			.member(host)
			.build();

		lenient().when(clock.instant()).thenReturn(NOW);
		lenient().when(memberRepository.findByIdAndStatus(MEMBER_ID, MemberStatus.ACTIVE))
			.thenReturn(Optional.of(guest));
		lenient().when(accommodationRepository.findQuoteSnapshotByIdAndStatus(
			ACCOMMODATION_ID, AccommodationStatus.PUBLISHED))
			.thenReturn(Optional.of(accommodation));
		lenient().when(bookingWindowProvider.currentFor("Asia/Seoul", NOW))
			.thenReturn(BookingWindow.startingOn(LocalDate.of(2026, 8, 25)));
		lenient().when(inventoryService.isRangeAvailableSnapshot(
			anyLong(), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
			.thenReturn(true);
		lenient().when(quoteRepository.save(any(ReservationQuote.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	@DisplayName("견적은 가격과 쿠폰 할인을 정확히 계산하지만 예약·재고·쿠폰을 점유하지 않는다")
	void createsExactNoHoldQuoteWithoutMutatingReservationOrCoupon() {
		ReservationRequest.Quote request = quoteRequest(COUPON_ID);
		given(couponUsageService.preview(MEMBER_ID, COUPON_ID, 360_000L))
			.willReturn(30_000L);

		ReservationResponse.Quote response = quoteService.createQuote(request, MEMBER_ID);

		assertThat(response.accommodationId()).isEqualTo(ACCOMMODATION_ID);
		assertThat(response.orderName()).isEqualTo("한강 전망 숙소");
		assertThat(response.checkIn()).isEqualTo(request.checkInDate());
		assertThat(response.checkOut()).isEqualTo(request.checkOutDate());
		assertThat(response.guestCount()).isEqualTo(2);
		assertThat(response.nightlyPrice()).isEqualTo(120_000L);
		assertThat(response.nights()).isEqualTo(3L);
		assertThat(response.subtotal()).isEqualTo(360_000L);
		assertThat(response.discountAmount()).isEqualTo(30_000L);
		assertThat(response.amount()).isEqualTo(330_000L);
		assertThat(response.currency()).isEqualTo("KRW");
		assertThat(response.paymentRequired()).isTrue();
		assertThat(response.inventoryHeld()).isFalse();
		assertThat(response.quoteExpiresAt()).isEqualTo(NOW.plusSeconds(5 * 60));
		assertThat(response.serverTime()).isEqualTo(NOW);

		then(quoteRepository).should().save(quoteCaptor.capture());
		ReservationQuote savedQuote = quoteCaptor.getValue();
		assertThat(savedQuote.getMemberId()).isEqualTo(MEMBER_ID);
		assertThat(savedQuote.getCouponId()).isEqualTo(COUPON_ID);
		assertThat(savedQuote.getSubtotal()).isEqualTo(360_000L);
		assertThat(savedQuote.getDiscountAmount()).isEqualTo(30_000L);

		then(accommodationRepository).should()
			.findQuoteSnapshotByIdAndStatus(ACCOMMODATION_ID, AccommodationStatus.PUBLISHED);
		then(accommodationRepository).should(never()).findByIdForUpdate(anyLong());
		then(inventoryService).should().isRangeAvailableSnapshot(
			ACCOMMODATION_ID, request.checkInDate(), request.checkOutDate(), NOW);
		then(couponUsageService).should().preview(MEMBER_ID, COUPON_ID, 360_000L);
		then(couponUsageService).should(never())
			.use(anyLong(), anyLong(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("쿠폰을 고르지 않은 견적은 쿠폰 저장소를 건드리지 않고 소계를 그대로 결제 금액으로 쓴다")
	void createsQuoteWithoutCouponPreviewWhenCouponIsAbsent() {
		ReservationResponse.Quote response = quoteService.createQuote(quoteRequest(null), MEMBER_ID);

		assertThat(response.subtotal()).isEqualTo(360_000L);
		assertThat(response.discountAmount()).isZero();
		assertThat(response.amount()).isEqualTo(360_000L);
		then(couponUsageService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("DST gap으로 존재하지 않는 숙소 현지 checkout 시각은 quote 단계에서 거절한다")
	void rejectsQuoteWhenLocalCheckoutTimeDoesNotExist() {
		accommodation = Accommodation.builder()
			.id(ACCOMMODATION_ID)
			.accommodationUid(UUID.fromString("be72e94f-1fa0-4ed4-a071-56051dc89c7e"))
			.name("파리 숙소")
			.basePrice(120_000L)
			.currency("EUR")
			.timeZoneId("Europe/Paris")
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(2, 30))
			.occupancyPolicy(OccupancyPolicy.builder().maxOccupancy(4).build())
			.status(AccommodationStatus.PUBLISHED)
			.member(Member.builder().id(3L).build())
			.build();
		given(accommodationRepository.findQuoteSnapshotByIdAndStatus(
			ACCOMMODATION_ID, AccommodationStatus.PUBLISHED))
			.willReturn(Optional.of(accommodation));
		given(bookingWindowProvider.currentFor("Europe/Paris", NOW))
			.willReturn(BookingWindow.startingOn(LocalDate.of(2027, 3, 1)));
		ReservationRequest.Quote request = new ReservationRequest.Quote(
			ACCOMMODATION_ID,
			LocalDate.of(2027, 3, 27),
			LocalDate.of(2027, 3, 28),
			2,
			null
		);

		assertThatThrownBy(() -> quoteService.createQuote(request, MEMBER_ID))
			.isInstanceOf(InvalidReservationLocalTimeException.class);

		then(quoteRepository).shouldHaveNoInteractions();
		then(couponUsageService).shouldHaveNoInteractions();
	}

	private ReservationRequest.Quote quoteRequest(Long couponId) {
		return new ReservationRequest.Quote(
			ACCOMMODATION_ID,
			LocalDate.of(2026, 9, 10),
			LocalDate.of(2026, 9, 13),
			2,
			couponId
		);
	}
}
