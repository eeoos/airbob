package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.PaymentAttempt;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.repository.PaymentAttemptRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class VirtualAccountService {

	private final ReservationRepository reservationRepository;
	private final PaymentAttemptRepository paymentAttemptRepository;

	private final TossPaymentsAdapter tossPaymentsAdapter;
	@Transactional
	public TossPaymentResponse issueVirtualAccount(String reservationUid, PaymentRequest.VirtualAccount request) {
		log.info("[가상계좌 발급]: Reservation UID {}", reservationUid);

		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		try {
			TossPaymentResponse response = tossPaymentsAdapter.issueVirtualAccount(reservation, request.bankCode(),
				request.customerName());
			PaymentAttempt attempt = PaymentAttempt.create(response, reservation);
			paymentAttemptRepository.save(attempt);
			log.info("[가상계좌 발급 완료]: Reservation UID {}", reservationUid);
			return response;
		} catch (TossPaymentException e) {
			log.error("[가상계좌 발급 실패] 재시도 불가 오류. Reservation UID: {}, Code: {}", reservationUid, e.getErrorCode().name(),
				e);
			// 가상계좌 발급 실패는 Saga의 시작 부분이므로, 사용자에게 직접 에러를 전달
			throw e;
		} catch (ResourceAccessException e) {
			log.error("[가상계좌 발급 실패] 재시도 소진. Reservation UID: {}.", reservationUid, e);
			throw e; // 컨트롤러로 예외를 전달하여 5xx 에러 응답
		}
	}
}
