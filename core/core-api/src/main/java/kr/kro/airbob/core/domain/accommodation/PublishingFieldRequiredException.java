package kr.kro.airbob.core.domain.accommodation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;
import lombok.Getter;

@Getter
public class PublishingFieldRequiredException extends BaseException {

	private final String fieldName;
	private final String reason;

	public PublishingFieldRequiredException(String fieldName) {
		super(ErrorCode.PUBLISHING_VALIDATION_FAILED); // 공통 ErrorCode 사용
		this.fieldName = fieldName;
		this.reason = fieldName + "은(는) 필수 입력 항목입니다.";
	}

	public PublishingFieldRequiredException(String fieldName, String reason) {
		super(ErrorCode.PUBLISHING_VALIDATION_FAILED);
		this.fieldName = fieldName;
		this.reason = reason;
	}
}
