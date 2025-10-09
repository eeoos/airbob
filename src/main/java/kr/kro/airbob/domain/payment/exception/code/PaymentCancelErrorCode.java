package kr.kro.airbob.domain.payment.exception.code;

import java.util.Arrays;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PaymentCancelErrorCode implements TossErrorCode {

	// 400
	ALREADY_CANCELED_PAYMENT(HttpStatus.BAD_REQUEST, "이미 취소된 결제 입니다."),
	INVALID_REFUND_ACCOUNT_INFO(HttpStatus.BAD_REQUEST, "환불 계좌번호와 예금주명이 일치하지 않습니다."),
	EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT(HttpStatus.BAD_REQUEST, "즉시할인금액보다 적은 금액은 부분취소가 불가능합니다."),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
	INVALID_REFUND_ACCOUNT_NUMBER(HttpStatus.BAD_REQUEST, "잘못된 환불 계좌번호입니다."),
	INVALID_BANK(HttpStatus.BAD_REQUEST, "유효하지 않은 은행입니다."),
	NOT_MATCHES_REFUNDABLE_AMOUNT(HttpStatus.BAD_REQUEST, "잔액 결과가 일치하지 않습니다."),
	PROVIDER_ERROR(HttpStatus.BAD_REQUEST, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
	REFUND_REJECTED(HttpStatus.BAD_REQUEST, "환불이 거절됐습니다. 결제사에 문의 부탁드립니다."),
	ALREADY_REFUND_PAYMENT(HttpStatus.BAD_REQUEST, "이미 환불된 결제입니다."),
	FORBIDDEN_BANK_REFUND_REQUEST(HttpStatus.BAD_REQUEST, "고객 계좌가 입금이 되지 않는 상태입니다."),

	// 401
	UNAUTHORIZED_KEY(HttpStatus.UNAUTHORIZED, "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."),

	// 403
	NOT_CANCELABLE_AMOUNT(HttpStatus.FORBIDDEN, "취소 할 수 없는 금액 입니다."),
	FORBIDDEN_CONSECUTIVE_REQUEST(HttpStatus.FORBIDDEN, "반복적인 요청은 허용되지 않습니다. 잠시 후 다시 시도해주세요."),
	FORBIDDEN_REQUEST(HttpStatus.FORBIDDEN, "허용되지 않은 요청입니다."),
	NOT_CANCELABLE_PAYMENT(HttpStatus.FORBIDDEN, "취소 할 수 없는 결제 입니다."),
	EXCEED_MAX_REFUND_DUE(HttpStatus.FORBIDDEN, "환불 가능한 기간이 지났습니다."),
	NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT(HttpStatus.FORBIDDEN, "입금 대기중인 결제는 부분 환불이 불가합니다."),
	NOT_ALLOWED_PARTIAL_REFUND(HttpStatus.FORBIDDEN,
		"에스크로 주문, 현금 카드 결제일 때는 부분 환불이 불가합니다. 이외 다른 결제 수단에서 부분 취소가 되지 않을 때는 토스페이먼츠에 문의해 주세요."),
	NOT_AVAILABLE_BANK(HttpStatus.FORBIDDEN, "은행 서비스 시간이 아닙니다."),
	INCORRECT_BASIC_AUTH_FORMAT(HttpStatus.FORBIDDEN, "잘못된 요청입니다. ':' 를 포함해 인코딩해주세요."),
	NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER(HttpStatus.FORBIDDEN, "휴면 처리된 회원의 결제는 취소할 수 없습니다."),

	// 404
	NOT_FOUND_PAYMENT(HttpStatus.NOT_FOUND, "존재하지 않는 결제 정보 입니다."),

	// 500
	FAILED_INTERNAL_SYSTEM_PROCESSING(HttpStatus.INTERNAL_SERVER_ERROR, "내부 시스템 처리 작업이 실패했습니다. 잠시 후 다시 시도해주세요."),
	FAILED_REFUND_PROCESS(HttpStatus.INTERNAL_SERVER_ERROR, "은행 응답시간 지연이나 일시적인 오류로 환불요청에 실패했습니다."),
	FAILED_METHOD_HANDLING_CANCEL(HttpStatus.INTERNAL_SERVER_ERROR, "취소 중 결제 시 사용한 결제 수단 처리과정에서 일시적인 오류가 발생했습니다."),
	FAILED_PARTIAL_REFUND(HttpStatus.INTERNAL_SERVER_ERROR, "은행 점검, 해약 계좌 등의 사유로 부분 환불이 실패했습니다."),
	COMMON_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
	FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING(HttpStatus.INTERNAL_SERVER_ERROR, "결제가 완료되지 않았어요. 다시 시도해주세요."),

	// custom
	PAYMENT_CANCEL_ERROR_MISMATCH_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "내부 시스템 처리 작업이 실패했습니다. 관리자에게 문의해주세요."),
	;

	private final HttpStatus statusCode;
	private final String errorCode;

	public static PaymentCancelErrorCode fromErrorCode(String errorCode) {
		return Arrays.stream(values())
			.filter(e -> e.name().equalsIgnoreCase(errorCode))
			.findAny()
			.orElse(PAYMENT_CANCEL_ERROR_MISMATCH_ERROR);
	}
}
