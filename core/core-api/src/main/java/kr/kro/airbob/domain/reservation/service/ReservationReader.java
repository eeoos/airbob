package kr.kro.airbob.domain.reservation.service;

import java.time.Instant;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.accommodation.port.AccommodationReservationReader;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;

/** 예약 조회 구현 컴포넌트. 다른 도메인은 ReservationRepository 대신 이 컴포넌트로 읽는다. */
@Component
@RequiredArgsConstructor
public class ReservationReader implements AccommodationReservationReader {

	private final ReservationRepository reservationRepository;

	/** 회원이 이 숙소에 묵고 체크아웃까지 마친 예약이 있는지. 잠그지 않는 조회다. */
	public boolean existsPastCompletedReservationByGuest(Long accommodationId, Long memberId, Instant now) {
		return reservationRepository.existsPastCompletedReservationByGuest(accommodationId, memberId, now);
	}

	@Override
	public boolean existsFutureInventoryReservation(Long accommodationId, Instant now) {
		return reservationRepository.existsFutureInventoryReservation(accommodationId, now);
	}
}
