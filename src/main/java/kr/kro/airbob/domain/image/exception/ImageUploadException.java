package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class ImageUploadException extends BaseException {
	public ImageUploadException(String fileName) {
		super(fileName, ErrorCode.IMAGE_UPLOAD_FAILED);
	}
}
