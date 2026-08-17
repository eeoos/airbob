package kr.kro.airbob.domain.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Cancellation;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Status;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.reservation.service.ReservationService;

class ReservationControllerTest {

	private final ReservationService reservationService = mock(ReservationService.class);
	private final ReservationController controller = new ReservationController(reservationService);

	@Test
	void paidCancellationReturnsAcceptedOperationStatusContract() {
		String reservationUid = UUID.randomUUID().toString();
		UUID operationUid = UUID.randomUUID();
		PaymentRequest.Cancel request = new PaymentRequest.Cancel("게스트 요청", null);
		Cancellation cancellation = new Cancellation(
			operationUid,
			Status.PENDING,
			"/api/v1/payment-operations/" + operationUid,
			false
		);
		given(reservationService.cancelReservation(reservationUid, request, 7L))
			.willReturn(cancellation);

		var response = controller.cancelReservation(reservationUid, request, 7L);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getData()).isEqualTo(cancellation);
		then(reservationService).should().cancelReservation(reservationUid, request, 7L);
	}

	@Test
	void complimentaryCancellationReturnsSynchronousSuccessContract() {
		String reservationUid = UUID.randomUUID().toString();
		PaymentRequest.Cancel request = new PaymentRequest.Cancel("0원 예약 취소", null);
		given(reservationService.cancelReservation(reservationUid, request, 7L))
			.willReturn(Cancellation.completed());

		var response = controller.cancelReservation(reservationUid, request, 7L);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getData().completedSynchronously()).isTrue();
		assertThat(response.getBody().getData().status()).isEqualTo(Status.SUCCEEDED);
	}
}
