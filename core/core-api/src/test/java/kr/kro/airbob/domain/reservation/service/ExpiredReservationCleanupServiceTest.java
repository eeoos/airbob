package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryBatchWriter;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExpiredReservationCleanupService 테스트")
class ExpiredReservationCleanupServiceTest {
	private static final Instant NOW = Instant.parse("2026-07-21T10:00:00Z");
	private static final int CLEANUP_BATCH_SIZE = 100;

	@Mock
	private ReservationRepository reservationRepository;
	@Mock
	private ReservationHistoryBatchWriter batchWriter;
	@Mock
	private CouponUsageService couponUsageService;
	@Mock
	private ReservationInventoryService inventoryService;

	private ExpiredReservationCleanupService service;
	private Reservation first;
	private Reservation second;

	@BeforeEach
	void setUp() {
		service = new ExpiredReservationCleanupService(
			reservationRepository,
			batchWriter,
			couponUsageService,
			inventoryService,
			Clock.fixed(NOW, ZoneOffset.UTC),
			CLEANUP_BATCH_SIZE
		);
		first = pendingReservation(1L, 11L, LocalDate.of(2026, 8, 1));
		second = pendingReservation(2L, 12L, LocalDate.of(2026, 8, 3));
	}

	@Test
	@DisplayName("만료한 예약의 쿠폰을 이력 저장과 같은 트랜잭션에서 일괄 복원한다")
	void restoresCouponsForEveryExpiredReservation() {
		given(reservationRepository.findExpiredPendingBatchForCleanup(
			NOW,
			CLEANUP_BATCH_SIZE
		)).willReturn(List.of(first, second));

		service.cleanupExpiredPendingReservations();

		InOrder inOrder = inOrder(inventoryService, couponUsageService, batchWriter);
		inOrder.verify(inventoryService).releaseHeldIfOwned(
			first.getAccommodation().getId(), first.getCheckInDate(), first.getCheckOutDate(), first.getId());
		inOrder.verify(inventoryService).releaseHeldIfOwned(
			second.getAccommodation().getId(), second.getCheckInDate(), second.getCheckOutDate(), second.getId());
		inOrder.verify(couponUsageService).restoreAll(List.of(first.getId(), second.getId()));
		inOrder.verify(batchWriter).writeAll(anyList(), eq(NOW));
	}

	@Test
	@DisplayName("만료 시각과 현재 시각이 같으면 만료 대상으로 조회한다")
	void expiresReservationAtExactBoundary() {
		given(reservationRepository.findExpiredPendingBatchForCleanup(
			NOW,
			CLEANUP_BATCH_SIZE
		)).willReturn(List.of(first));

		int cleaned = service.cleanupExpiredPendingReservations();

		assertThat(cleaned).isEqualTo(1);
		then(reservationRepository).should().findExpiredPendingBatchForCleanup(
			NOW,
			CLEANUP_BATCH_SIZE
		);
	}

	@Test
	@DisplayName("만료된 예약 이력을 동일한 기준 시각으로 일괄 저장한다")
	void writesExpiredHistoriesWithTheSameCutoff() {
		given(reservationRepository.findExpiredPendingBatchForCleanup(
			any(Instant.class),
			eq(CLEANUP_BATCH_SIZE)
		)).willReturn(List.of(first, second));

		int cleaned = service.cleanupExpiredPendingReservations();

		assertThat(cleaned).isEqualTo(2);
		ArgumentCaptor<List<ReservationHistory>> histories = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<Instant> historyCreatedAt = ArgumentCaptor.forClass(Instant.class);
		then(batchWriter).should().writeAll(histories.capture(), historyCreatedAt.capture());
		assertThat(histories.getValue()).extracting(ReservationHistory::getStatus)
			.containsOnly(ReservationStatus.EXPIRED);
		ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
		then(reservationRepository).should().findExpiredPendingBatchForCleanup(
			cutoff.capture(),
			eq(CLEANUP_BATCH_SIZE)
		);
		assertThat(historyCreatedAt.getValue()).isEqualTo(cutoff.getValue());
	}

	@Test
	@DisplayName("history batch 실패를 호출자에게 전달한다")
	void propagatesHistoryBatchFailure() {
		given(reservationRepository.findExpiredPendingBatchForCleanup(any(), anyInt()))
			.willReturn(List.of(first, second));
		willThrow(new DataIntegrityViolationException("intentional"))
			.given(batchWriter).writeAll(anyList(), any(Instant.class));

		assertThatThrownBy(service::cleanupExpiredPendingReservations)
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("만료된 예약이 없으면 history를 저장하거나 쿠폰을 복원하지 않는다")
	void doesNothingWhenNoExpiredReservationsExist() {
		given(reservationRepository.findExpiredPendingBatchForCleanup(any(), anyInt()))
			.willReturn(List.of());

		int cleaned = service.cleanupExpiredPendingReservations();

		assertThat(cleaned).isZero();
		then(batchWriter).shouldHaveNoInteractions();
		then(couponUsageService).shouldHaveNoInteractions();
		then(inventoryService).shouldHaveNoInteractions();
	}

	private Reservation pendingReservation(long reservationId, long accommodationId, LocalDate checkIn) {
		Accommodation accommodation = Accommodation.builder()
			.id(accommodationId)
			.build();
		Member guest = Member.builder()
			.id(100L + reservationId)
			.build();
		return Reservation.builder()
			.id(reservationId)
			.reservationUid(UUID.randomUUID())
			.reservationCode("R" + reservationId)
			.accommodation(accommodation)
			.guest(guest)
			.checkInDate(checkIn)
			.checkOutDate(checkIn.plusDays(1))
			.checkInAt(checkIn.atTime(15, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant())
			.checkOutAt(checkIn.plusDays(1).atTime(11, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant())
			.timeZoneId("Asia/Seoul")
			.guestCount(2)
			.totalPrice(100_000L)
			.currency("KRW")
			.status(ReservationStatus.PAYMENT_PENDING)
			.expiresAt(NOW)
			.createdAt(LocalDateTime.of(2026, 7, 1, 9, 0))
			.updatedAt(LocalDateTime.of(2026, 7, 1, 9, 0))
			.build();
	}
}
