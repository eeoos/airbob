package kr.kro.airbob.domain.payment.exception;

import java.util.Arrays;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PaymentInquiryErrorCode {

	// 400
	NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN_BELOW_AMOUNT(HttpStatus.BAD_REQUEST, "5만원 이하의 결제는 할부가 불가능해서 결제에 실패했습니다."),

	// 401
	UNAUTHORIZED_KEY(HttpStatus.UNAUTHORIZED, "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."),

	// 403
	FORBIDDEN_CONSECUTIVE_REQUEST(HttpStatus.FORBIDDEN, "반복적인 요청은 허용되지 않습니다. 잠시 후 다시 시도해주세요."),
	INCORRECT_BASIC_AUTH_FORMAT(HttpStatus.FORBIDDEN, "잘못된 요청입니다. ':' 를 포함해 인코딩해주세요."),

	// 404
	NOT_FOUND_PAYMENT(HttpStatus.NOT_FOUND, "존재하지 않는 결제 정보 입니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 정보 입니다."),

	// 500
	FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING(HttpStatus.INTERNAL_SERVER_ERROR, "결제가 완료되지 않았어요. 다시 시도해주세요."),

	// custom
	PAYMENT_INQUIRY_ERROR_MISMATCH_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "내부 시스템 처리 작업이 실패했습니다. 관리자에게 문의해주세요.");

	private final HttpStatus statusCode;
	private final String errorCode;

	public static PaymentInquiryErrorCode fromErrorCode(String errorCode) {
		return Arrays.stream(values())
			.filter(e -> e.name().equalsIgnoreCase(errorCode))
			.findAny()
			.orElse(PAYMENT_INQUIRY_ERROR_MISMATCH_ERROR);
	}

}
