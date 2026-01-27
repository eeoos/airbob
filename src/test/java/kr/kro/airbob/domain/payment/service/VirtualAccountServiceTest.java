package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.PaymentAttempt;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.exception.code.VirtualAccountIssueErrorCode;
import kr.kro.airbob.domain.payment.repository.PaymentAttemptRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("VirtualAccountService 테스트")
class VirtualAccountServiceTest {

	@InjectMocks
	private VirtualAccountService virtualAccountService;

	@Mock
	private ReservationRepository reservationRepository;

	@Mock
	private PaymentAttemptRepository paymentAttemptRepository;

	@Mock
	private TossPaymentsAdapter tossPaymentsAdapter;

	@Captor
	private ArgumentCaptor<PaymentAttempt> attemptCaptor;

	private UUID reservationUid;
	private Reservation reservation;
	private PaymentRequest.VirtualAccount virtualAccountRequest;

	@BeforeEach
	void setUp() {
		reservationUid = UUID.randomUUID();

		reservation = Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.totalPrice(100_000L)
			.build();

		virtualAccountRequest = new PaymentRequest.VirtualAccount("088", "홍길동");
	}

	@Nested
	@DisplayName("issueVirtualAccount 테스트")
	class IssueVirtualAccountTest {

		@Test
		@DisplayName("정상 발급 시 PaymentAttempt가 저장된다")
		void 정상_발급_PaymentAttempt_저장() {
			// given
			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			TossPaymentResponse.VirtualAccount virtualAccount = TossPaymentResponse.VirtualAccount.builder()
				.bankCode("088")
				.accountNumber("123456789")
				.customerName("홍길동")
				.dueDate(ZonedDateTime.now().plusHours(24))
				.build();

			TossPaymentResponse response = TossPaymentResponse.builder()
				.paymentKey("pk_va_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.method("가상계좌")
				.status("WAITING_FOR_DEPOSIT")
				.virtualAccount(virtualAccount)
				.build();

			given(tossPaymentsAdapter.issueVirtualAccount(any(Reservation.class), anyString(), anyString()))
				.willReturn(response);

			// when
			TossPaymentResponse result = virtualAccountService.issueVirtualAccount(
				reservationUid.toString(), virtualAccountRequest
			);

			// then
			then(paymentAttemptRepository).should().save(attemptCaptor.capture());
			PaymentAttempt savedAttempt = attemptCaptor.getValue();

			assertThat(savedAttempt.getPaymentKey()).isEqualTo("pk_va_123");
			assertThat(savedAttempt.getVirtualBankCode()).isEqualTo("088");
			assertThat(savedAttempt.getVirtualAccountNumber()).isEqualTo("123456789");
		}

		@Test
		@DisplayName("정상 발급 시 TossPaymentResponse가 반환된다")
		void 정상_발급_응답_반환() {
			// given
			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			TossPaymentResponse.VirtualAccount virtualAccount = TossPaymentResponse.VirtualAccount.builder()
				.bankCode("088")
				.accountNumber("123456789")
				.customerName("홍길동")
				.dueDate(ZonedDateTime.now().plusHours(24))
				.build();

			TossPaymentResponse response = TossPaymentResponse.builder()
				.paymentKey("pk_va_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.method("가상계좌")
				.status("WAITING_FOR_DEPOSIT")
				.virtualAccount(virtualAccount)
				.build();

			given(tossPaymentsAdapter.issueVirtualAccount(any(Reservation.class), anyString(), anyString()))
				.willReturn(response);

			// when
			TossPaymentResponse result = virtualAccountService.issueVirtualAccount(
				reservationUid.toString(), virtualAccountRequest
			);

			// then
			assertThat(result.getPaymentKey()).isEqualTo("pk_va_123");
			assertThat(result.getStatus()).isEqualTo("WAITING_FOR_DEPOSIT");
		}

		@Test
		@DisplayName("예약이 존재하지 않으면 ReservationNotFoundException이 발생한다")
		void 예약_없음_예외() {
			// given
			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> virtualAccountService.issueVirtualAccount(
				reservationUid.toString(), virtualAccountRequest
			)).isInstanceOf(ReservationNotFoundException.class);
		}

		@Test
		@DisplayName("Toss API 실패 시 TossPaymentException이 발생한다")
		void Toss_API_실패() {
			// given
			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			TossPaymentException tossException = new TossPaymentException(
				VirtualAccountIssueErrorCode.DUPLICATED_ORDER_ID
			);
			given(tossPaymentsAdapter.issueVirtualAccount(any(Reservation.class), anyString(), anyString()))
				.willThrow(tossException);

			// when & then
			assertThatThrownBy(() -> virtualAccountService.issueVirtualAccount(
				reservationUid.toString(), virtualAccountRequest
			)).isInstanceOf(TossPaymentException.class);
		}

		@Test
		@DisplayName("네트워크 오류 시 ResourceAccessException이 발생한다")
		void 네트워크_오류() {
			// given
			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			given(tossPaymentsAdapter.issueVirtualAccount(any(Reservation.class), anyString(), anyString()))
				.willThrow(new ResourceAccessException("재시도 소진"));

			// when & then
			assertThatThrownBy(() -> virtualAccountService.issueVirtualAccount(
				reservationUid.toString(), virtualAccountRequest
			)).isInstanceOf(ResourceAccessException.class);
		}

		@Test
		@DisplayName("bankCode와 customerName이 올바르게 전달된다")
		void 파라미터_전달_검증() {
			// given
			given(reservationRepository.findByReservationUid(reservationUid))
				.willReturn(Optional.of(reservation));

			TossPaymentResponse response = TossPaymentResponse.builder()
				.paymentKey("pk_va_123")
				.orderId(reservationUid.toString())
				.totalAmount(100_000L)
				.method("가상계좌")
				.status("WAITING_FOR_DEPOSIT")
				.build();

			given(tossPaymentsAdapter.issueVirtualAccount(any(Reservation.class), anyString(), anyString()))
				.willReturn(response);

			// when
			virtualAccountService.issueVirtualAccount(reservationUid.toString(), virtualAccountRequest);

			// then
			then(tossPaymentsAdapter).should().issueVirtualAccount(
				eq(reservation),
				eq("088"),
				eq("홍길동")
			);
		}
	}
}
