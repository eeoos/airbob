package kr.kro.airbob.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	// common
	INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "유효하지 않은 입력 값입니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C002", "지원하지 않는 HTTP 메서드입니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C003", "서버 내부에서 처리할 수 없는 오류가 발생했습니다."),
	INVALID_TYPE_VALUE(HttpStatus.BAD_REQUEST, "C004", "유효하지 않은 타입 값입니다."),
	CURSOR_ENCODING_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C005", "커서 인코딩 중 오류가 발생했습니다."),
	CURSOR_PAGE_SIZE_INVALID(HttpStatus.BAD_REQUEST, "C006", "커서 페이지 크기는 1 이상이어야 합니다."),
	OUTBOX_PUBLISH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "C007", "요청을 처리하는 중 내부 이벤트 시스템에 오류가 발생했습니다."),

	// auth & member
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "M001", "존재하지 않는 사용자입니다."),
	EMAIL_DUPLICATION(HttpStatus.CONFLICT, "M002", "이미 존재하는 이메일입니다."),
	INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "M003", "비밀번호가 일치하지 않습니다."),
	UNAUTHORIZED_ACCESS(HttpStatus.UNAUTHORIZED, "M004", "인증이 필요합니다."),
	HOST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "M005", "숙소에 대한 수정/삭제 권한이 없는 호스트입니다."),


	// accommodation
	ACCOMMODATION_NOT_FOUND(HttpStatus.NOT_FOUND, "A001", "존재하지 않는 숙소입니다."),


	// reservation
	RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "R001", "존재하지 않는 예약입니다."),
	RESERVATION_CONFLICT(HttpStatus.CONFLICT, "R002", "해당 날짜는 다른 예약과 겹쳐 예약이 불가능합니다."), // 날짜 중복
	RESERVATION_LOCK_FAILED(HttpStatus.CONFLICT, "R003", "동시에 많은 예약이 시도되어 처리하지 못했습니다. 잠시 후 다시 시도해주세요."), // 분산 락 실패 (동시성 이슈)
	CANNOT_CANCEL_RESERVATION(HttpStatus.CONFLICT, "R004", "결제 완료 상태의 예약만 취소할 수 있습니다."),
	CANNOT_CONFIRM_RESERVATION(HttpStatus.CONFLICT, "R005", "결제 대기 상태의 예약만 확정할 수 있습니다."),
	CANNOT_EXPIRE_RESERVATION(HttpStatus.CONFLICT, "R006", "결제 대기 상태의 예약만 만료시킬 수 있습니다."),
	RESERVATION_STATE_CHANGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "R007", "예약 상태를 변경하는 중 내부 서버 오류가 발생했습니다."),


	// payment
	PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "존재하지 않는 결제 정보입니다."),
	TOSS_PAYMENT_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "P002", "Toss Payments 예외"),
	// TOSS_PAYMENT_CONFIRM_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "P002", "Toss Payments 결제 승인에 실패했습니다."),
	// TOSS_PAYMENT_CANCEL_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "P003", "Toss Payments 결제 취소에 실패했습니다."),
	// TOSS_PAYMENT_INQUIRY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "P004", "Toss Payments 결제 조회에 실패했습니다."),
	// TOSS_VIRTUAL_ACCOUNT_ISSUE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "P005", "Toss Payments 가상계좌 발급에 실패했습니다."),


	// wishlist
	WISHLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "W001", "존재하지 않는 위시리스트입니다."),
	WISHLIST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "W002", "위시리스트에 대한 접근 권한이 없습니다."),
	WISHLIST_ACCOMMODATION_NOT_FOUND(HttpStatus.NOT_FOUND, "W003", "존재하지 않는 위시리스트 항목입니다."),
	WISHLIST_ACCOMMODATION_DUPLICATE(HttpStatus.CONFLICT, "W004", "이미 위시리스트에 추가된 숙소입니다."),
	WISHLIST_ACCOMMODATION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "W005", "위시리스트 항목에 대한 접근 권한이 없습니다."),


	// review
	REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "V001", "존재하지 않는 리뷰입니다."),
	REVIEW_SUMMARY_NOT_FOUND(HttpStatus.NOT_FOUND, "V002", "리뷰 요약 정보를 찾을 수 없습니다."),
	REVIEW_CREATION_DENIED(HttpStatus.FORBIDDEN, "V003", "리뷰를 작성할 권한이 없습니다."),
	REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "V004", "이미 리뷰를 작성했습니다."),

	// discount
	DISCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "D001", "존재하지 않는 할인정책입니다.");


	private final HttpStatus status;
	private final String code;
	private final String message;
}
