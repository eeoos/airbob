package kr.kro.airbob.domain.payment.exception;

import java.util.Arrays;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum VirtualAccountIssueErrorCode {

	// 400
	DUPLICATED_ORDER_ID(HttpStatus.BAD_REQUEST, "이미 승인 및 취소가 진행된 중복된 주문번호 입니다. 다른 주문번호로 진행해주세요."),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
	INVALID_REGISTRATION_NUMBER_TYPE(HttpStatus.BAD_REQUEST, "유효하지 않은 등록 번호 타입입니다."),
	INVALID_DATE(HttpStatus.BAD_REQUEST, "날짜 데이터가 잘못 되었습니다."),
	INVALID_BANK(HttpStatus.BAD_REQUEST, "유효하지 않은 은행입니다."),
	EXCEED_MAX_DUE_DATE(HttpStatus.BAD_REQUEST, "가상 계좌의 최대 유효만료 기간을 초과했습니다."),

	// 401
	UNAUTHORIZED_KEY(HttpStatus.UNAUTHORIZED, "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."),

	// 403
	INCORRECT_BASIC_AUTH_FORMAT(HttpStatus.FORBIDDEN, "잘못된 요청입니다. ':' 를 포함해 인코딩해주세요."),

	// 500
	FAILED_INTERNAL_SYSTEM_PROCESSING(HttpStatus.INTERNAL_SERVER_ERROR, "내부 시스템 처리 작업이 실패했습니다. 잠시 후 다시 시도해주세요."),
	FAILED_DB_PROCESSING(HttpStatus.INTERNAL_SERVER_ERROR, "잘못된 요청 값으로 처리 중 DB 에러가 발생했습니다."),

	// custom
	VIRTUAL_ACCOUNT_ISSUE_ERROR_CODE(HttpStatus.INTERNAL_SERVER_ERROR, "내부 시스템 처리 작업이 실패했습니다. 관리자에게 문의해주세요.");

	private final HttpStatus statusCode;
	private final String errorCode;

	public static VirtualAccountIssueErrorCode fromErrorCode(String errorCode) {
		return Arrays.stream(values())
			.filter(e -> e.name().equalsIgnoreCase(errorCode))
			.findAny()
			.orElse(VIRTUAL_ACCOUNT_ISSUE_ERROR_CODE);
	}
}
