package kr.kro.airbob.domain.reservation.service;

import java.time.Instant;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.idempotency.ReservationCheckoutIdentity;
import kr.kro.airbob.domain.reservation.idempotency.ReservationCheckoutRequestClaim;
import kr.kro.airbob.domain.reservation.repository.ReservationCheckoutRequestStore;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;

/** 예약 저장과 체크아웃 멱등 요청 기록 구현 컴포넌트. 모두 호출한 쪽 트랜잭션 안에서 돈다. */
@Component
@RequiredArgsConstructor
public class ReservationWriter {

	private final ReservationRepository reservationRepository;
	private final ReservationCheckoutRequestStore checkoutRequestStore;

	/** 쓰이지 않은 6자리 예약 코드를 만든다. */
	public String newReservationCode() {
		String reservationCode;
		do {
			reservationCode = RandomStringUtils.randomAlphanumeric(6).toUpperCase();
		} while (reservationRepository.existsByReservationCode(reservationCode));
		return reservationCode;
	}

	/** 새 예약을 저장하고 바로 flush해 ID를 받고 제약 위반을 이 시점에 드러낸다. */
	public void create(Reservation reservation) {
		reservationRepository.saveAndFlush(reservation);
	}

	/** 회원의 멱등 키 요청 행을 잠그거나 새로 만든다. */
	public ReservationCheckoutRequestClaim claimCheckoutRequest(
		long memberId, ReservationCheckoutIdentity identity, Instant createdAt) {
		return checkoutRequestStore.lockOrCreate(memberId, identity, createdAt);
	}

	public void completeCheckoutRequest(long checkoutRequestId, long reservationId, Instant completedAt) {
		checkoutRequestStore.complete(checkoutRequestId, reservationId, completedAt);
	}
}
