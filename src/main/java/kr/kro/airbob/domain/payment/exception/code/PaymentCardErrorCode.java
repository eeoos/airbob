package kr.kro.airbob.domain.payment.exception.code;

import java.util.Arrays;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PaymentCardErrorCode implements TossErrorCode{

	// 400
	INVALID_CARD_EXPIRATION(HttpStatus.BAD_REQUEST, "카드 정보를 다시 확인해주세요. (유효기간)"),
	INVALID_CARD_NUMBER(HttpStatus.BAD_REQUEST, "카드번호를 다시 확인해주세요."),
	INVALID_CARD_PASSWORD(HttpStatus.BAD_REQUEST, "카드 정보를 다시 확인해주세요. (비밀번호)"),
	INVALID_CARD_IDENTITY(HttpStatus.BAD_REQUEST, "입력하신 주민번호/사업자번호가 카드 소유주 정보와 일치하지 않습니다."),
	INVALID_STOPPED_CARD(HttpStatus.BAD_REQUEST, "정지된 카드 입니다."),
	INVALID_REJECT_CARD(HttpStatus.BAD_REQUEST, "카드 사용이 거절되었습니다. 카드사 문의가 필요합니다."),
	INVALID_BIRTH_DAY_FORMAT(HttpStatus.BAD_REQUEST, "생년월일 정보는 6자리의 `yyMMdd` 형식이어야 합니다. 사업자등록번호는 10자리의 숫자여야 합니다."),
	NOT_SUPPORTED_CARD_TYPE(HttpStatus.BAD_REQUEST, "지원되지 않는 카드 종류입니다."),
	NOT_REGISTERED_CARD_COMPANY(HttpStatus.BAD_REQUEST, "카드를 사용 등록 후 이용해주세요."),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
	NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN(HttpStatus.BAD_REQUEST, "할부가 지원되지 않는 카드입니다."),
	INVALID_CARD_INSTALLMENT_PLAN(HttpStatus.BAD_REQUEST, "할부 개월 정보가 잘못되었습니다."),
	NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT(HttpStatus.BAD_REQUEST, "할부가 지원되지 않는 카드 또는 가맹점 입니다."),
	INVALID_EMAIL(HttpStatus.BAD_REQUEST, "유효하지 않은 이메일 주소 형식입니다."),
	BELOW_MINIMUM_AMOUNT(HttpStatus.BAD_REQUEST, "신용카드는 결제금액이 100원 이상, 계좌는 200원이상부터 결제가 가능합니다."),
	DUPLICATED_ORDER_ID(HttpStatus.BAD_REQUEST, "이미 승인 및 취소가 진행된 중복된 주문번호 입니다. 다른 주문번호로 진행해주세요."),
	INVALID_ORDER_ID(HttpStatus.BAD_REQUEST, "`orderId`는 영문 대소문자, 숫자, 특수문자(-, _) 만 허용합니다. 6자 이상 64자 이하여야 합니다."),
	NOT_ALLOWED_POINT_USE(HttpStatus.BAD_REQUEST, "포인트 사용이 불가한 카드로 카드 포인트 결제에 실패했습니다."),
	INVALID_REQUIRED_PARAM(HttpStatus.BAD_REQUEST, "필수 파라미터가 누락되었습니다."),
	NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN_BELOW_AMOUNT(HttpStatus.BAD_REQUEST, "5만원 이하의 결제는 할부가 불가능해서 결제에 실패했습니다."),

	// 401
	UNAUTHORIZED_KEY(HttpStatus.UNAUTHORIZED, "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."),

	// 403
	REJECT_CARD_PAYMENT(HttpStatus.FORBIDDEN, "한도초과 혹은 잔액부족으로 결제에 실패했습니다."),
	EXCEED_MAX_AUTH_COUNT(HttpStatus.FORBIDDEN, "최대 인증 횟수를 초과했습니다. 카드사로 문의해주세요."),
	REJECT_ACCOUNT_PAYMENT(HttpStatus.FORBIDDEN, "잔액부족으로 결제에 실패했습니다."),
	REJECT_CARD_COMPANY(HttpStatus.FORBIDDEN, "결제 승인이 거절되었습니다."),
	INCORRECT_BASIC_AUTH_FORMAT(HttpStatus.FORBIDDEN, "잘못된 요청입니다. ':' 를 포함해 인코딩해주세요."),

	// 500
	FAILED_INTERNAL_SYSTEM_PROCESSING(HttpStatus.INTERNAL_SERVER_ERROR, "내부 시스템 처리 작업이 실패했습니다. 잠시 후 다시 시도해주세요."),
	FAILED_DB_PROCESSING(HttpStatus.INTERNAL_SERVER_ERROR, "잘못된 요청 값으로 처리 중 DB 에러가 발생했습니다."),
	FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING(HttpStatus.INTERNAL_SERVER_ERROR, "결제가 완료되지 않았어요. 다시 시도해주세요."),
	FAILED_CARD_COMPANY_RESPONSE(HttpStatus.INTERNAL_SERVER_ERROR, "카드사에서 에러가 발생했습니다. 잠시 후 다시 시도해 주세요."),

	// custom
	PAYMENT_CARD_ERROR_MISMATCH_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "내부 시스템 처리 작업이 실패했습니다. 관리자에게 문의해주세요.");

	private final HttpStatus statusCode;
	private final String errorCode;

	public static PaymentCardErrorCode fromErrorCode(String errorCode) {
		return Arrays.stream(values())
			.filter(e -> e.name().equalsIgnoreCase(errorCode))
			.findAny()
			.orElse(PAYMENT_CARD_ERROR_MISMATCH_ERROR);
	}
}
