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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.dto.TossPaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.TossPaymentException;
import kr.kro.airbob.domain.payment.exception.code.PaymentInquiryErrorCode;
import kr.kro.airbob.domain.payment.repository.PaymentRepository;
import kr.kro.airbob.domain.reservation.entity.Reservation;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentQueryService 테스트")
class PaymentQueryServiceTest {

	@InjectMocks
	private PaymentQueryService paymentQueryService;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private TossPaymentsAdapter tossPaymentsAdapter;

	private UUID reservationUid;
	private Member guest;
	private Reservation reservation;
	private Payment payment;
	private Long memberId;
	private String paymentKey;
	private String orderId;

	@BeforeEach
	void setUp() {
		reservationUid = UUID.randomUUID();
		memberId = 1L;
		paymentKey = "pk_test_123";
		orderId = reservationUid.toString();

		guest = Member.builder()
			.id(memberId)
			.email("guest@test.com")
			.nickname("TestGuest")
			.build();

		reservation = Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.reservationCode("ABC123")
			.totalPrice(100_000L)
			.guest(guest)
			.build();

		TossPaymentResponse originalResponse = TossPaymentResponse.builder()
			.paymentKey(paymentKey)
			.orderId(orderId)
			.totalAmount(100_000L)
			.balanceAmount(100_000L)
			.method("카드")
			.status("DONE")
			.approvedAt(ZonedDateTime.now())
			.build();

		payment = Payment.create(originalResponse, reservation);
	}

	@Nested
	@DisplayName("findPaymentByPaymentKey 테스트")
	class FindPaymentByPaymentKeyTest {

		@Test
		@DisplayName("정상 조회 시 PaymentInfo를 반환한다")
		void 정상_조회() {
			// given
			given(paymentRepository.findByPaymentKey(paymentKey))
				.willReturn(Optional.of(payment));

			TossPaymentResponse tossResponse = TossPaymentResponse.builder()
				.paymentKey(paymentKey)
				.orderId(orderId)
				.totalAmount(100_000L)
				.build();

			given(tossPaymentsAdapter.getPaymentByPaymentKey(paymentKey))
				.willReturn(tossResponse);

			// when
			PaymentResponse.PaymentInfo result = paymentQueryService.findPaymentByPaymentKey(paymentKey, memberId);

			// then
			assertThat(result).isNotNull();
		}

		@Test
		@DisplayName("Payment가 없으면 PaymentNotFoundException이 발생한다")
		void Payment_없음_예외() {
			// given
			given(paymentRepository.findByPaymentKey(paymentKey))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> paymentQueryService.findPaymentByPaymentKey(paymentKey, memberId))
				.isInstanceOf(PaymentNotFoundException.class);
		}

		@Test
		@DisplayName("소유권 검증 실패 시 PaymentAccessDeniedException이 발생한다")
		void 소유권_검증_실패() {
			// given
			Long wrongMemberId = 999L;

			given(paymentRepository.findByPaymentKey(paymentKey))
				.willReturn(Optional.of(payment));

			// when & then
			assertThatThrownBy(() -> paymentQueryService.findPaymentByPaymentKey(paymentKey, wrongMemberId))
				.isInstanceOf(PaymentAccessDeniedException.class);
		}

		@Test
		@DisplayName("Toss API 에러 시 TossPaymentException이 발생한다")
		void Toss_API_에러() {
			// given
			given(paymentRepository.findByPaymentKey(paymentKey))
				.willReturn(Optional.of(payment));

			TossPaymentException tossException = new TossPaymentException(
				PaymentInquiryErrorCode.NOT_FOUND_PAYMENT
			);
			given(tossPaymentsAdapter.getPaymentByPaymentKey(paymentKey))
				.willThrow(tossException);

			// when & then
			assertThatThrownBy(() -> paymentQueryService.findPaymentByPaymentKey(paymentKey, memberId))
				.isInstanceOf(TossPaymentException.class);
		}

		@Test
		@DisplayName("외부 시스템 오류 시 ResourceAccessException이 발생한다")
		void 외부_시스템_오류() {
			// given
			given(paymentRepository.findByPaymentKey(paymentKey))
				.willReturn(Optional.of(payment));

			given(tossPaymentsAdapter.getPaymentByPaymentKey(paymentKey))
				.willThrow(new ResourceAccessException("네트워크 오류"));

			// when & then
			assertThatThrownBy(() -> paymentQueryService.findPaymentByPaymentKey(paymentKey, memberId))
				.isInstanceOf(ResourceAccessException.class);
		}
	}

	@Nested
	@DisplayName("findPaymentByOrderId 테스트")
	class FindPaymentByOrderIdTest {

		@Test
		@DisplayName("orderId로 정상 조회 시 PaymentInfo를 반환한다")
		void 정상_조회() {
			// given
			given(paymentRepository.findByOrderId(orderId))
				.willReturn(Optional.of(payment));

			TossPaymentResponse tossResponse = TossPaymentResponse.builder()
				.paymentKey(paymentKey)
				.orderId(orderId)
				.totalAmount(100_000L)
				.build();

			given(tossPaymentsAdapter.getPaymentByOrderId(orderId))
				.willReturn(tossResponse);

			// when
			PaymentResponse.PaymentInfo result = paymentQueryService.findPaymentByOrderId(orderId, memberId);

			// then
			assertThat(result).isNotNull();
		}

		@Test
		@DisplayName("Payment가 없으면 PaymentNotFoundException이 발생한다")
		void Payment_없음_예외() {
			// given
			given(paymentRepository.findByOrderId(orderId))
				.willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> paymentQueryService.findPaymentByOrderId(orderId, memberId))
				.isInstanceOf(PaymentNotFoundException.class);
		}

		@Test
		@DisplayName("소유권 검증 실패 시 PaymentAccessDeniedException이 발생한다")
		void 소유권_검증_실패() {
			// given
			Long wrongMemberId = 999L;

			given(paymentRepository.findByOrderId(orderId))
				.willReturn(Optional.of(payment));

			// when & then
			assertThatThrownBy(() -> paymentQueryService.findPaymentByOrderId(orderId, wrongMemberId))
				.isInstanceOf(PaymentAccessDeniedException.class);
		}
	}
}
