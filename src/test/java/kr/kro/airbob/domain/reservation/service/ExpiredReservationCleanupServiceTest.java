package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryBatchWriter;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExpiredReservationCleanupService 테스트")
class ExpiredReservationCleanupServiceTest {

	@Mock
	private ReservationRepository reservationRepository;
	@Mock
	private ReservationHistoryBatchWriter batchWriter;
	@Mock
	private ReservationHoldService holdService;

	private ExpiredReservationCleanupService service;
	private Reservation first;
	private Reservation second;

	@BeforeEach
	void setUp() {
		service = new ExpiredReservationCleanupService(reservationRepository, batchWriter, holdService);
		first = pendingReservation(1L, 11L, LocalDate.of(2026, 8, 1));
		second = pendingReservation(2L, 12L, LocalDate.of(2026, 8, 3));
	}

	@Test
	@DisplayName("모든 history batch가 성공한 뒤 hold를 제거한다")
	void removesHoldsOnlyAfterHistoryBatchSucceeds() {
		given(reservationRepository.findAllByStatusAndExpiresAtBefore(
			eq(ReservationStatus.PAYMENT_PENDING),
			any(LocalDateTime.class)
		)).willReturn(List.of(first, second));

		int cleaned = service.cleanupExpiredPendingReservations();

		assertThat(cleaned).isEqualTo(2);
		ArgumentCaptor<List<ReservationHistory>> histories = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<LocalDateTime> historyCreatedAt = ArgumentCaptor.forClass(LocalDateTime.class);
		InOrder order = inOrder(batchWriter, holdService);
		order.verify(batchWriter).writeAll(histories.capture(), historyCreatedAt.capture());
		order.verify(holdService).removeHold(
			eq(11L), eq(LocalDate.of(2026, 8, 1)), eq(LocalDate.of(2026, 8, 2))
		);
		order.verify(holdService).removeHold(
			eq(12L), eq(LocalDate.of(2026, 8, 3)), eq(LocalDate.of(2026, 8, 4))
		);
		assertThat(histories.getValue()).extracting(ReservationHistory::getStatus)
			.containsOnly(ReservationStatus.EXPIRED);
		ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
		then(reservationRepository).should().findAllByStatusAndExpiresAtBefore(
			eq(ReservationStatus.PAYMENT_PENDING),
			cutoff.capture()
		);
		assertThat(historyCreatedAt.getValue()).isEqualTo(cutoff.getValue());
	}

	@Test
	@DisplayName("history batch 실패 시 hold를 제거하지 않는다")
	void doesNotRemoveHoldsWhenHistoryBatchFails() {
		given(reservationRepository.findAllByStatusAndExpiresAtBefore(any(), any()))
			.willReturn(List.of(first, second));
		willThrow(new DataIntegrityViolationException("intentional"))
			.given(batchWriter).writeAll(anyList(), any(LocalDateTime.class));

		assertThatThrownBy(service::cleanupExpiredPendingReservations)
			.isInstanceOf(DataIntegrityViolationException.class);

		then(holdService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("만료된 예약이 없으면 history를 저장하거나 hold를 제거하지 않는다")
	void doesNothingWhenNoExpiredReservationsExist() {
		given(reservationRepository.findAllByStatusAndExpiresAtBefore(any(), any()))
			.willReturn(List.of());

		int cleaned = service.cleanupExpiredPendingReservations();

		assertThat(cleaned).isZero();
		then(batchWriter).shouldHaveNoInteractions();
		then(holdService).shouldHaveNoInteractions();
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
			.checkIn(checkIn.atTime(15, 0))
			.checkOut(checkIn.plusDays(1).atTime(11, 0))
			.guestCount(2)
			.totalPrice(100_000L)
			.currency("KRW")
			.status(ReservationStatus.PAYMENT_PENDING)
			.expiresAt(LocalDateTime.of(2026, 7, 21, 10, 0))
			.createdAt(LocalDateTime.of(2026, 7, 1, 9, 0))
			.updatedAt(LocalDateTime.of(2026, 7, 1, 9, 0))
			.build();
	}
}
