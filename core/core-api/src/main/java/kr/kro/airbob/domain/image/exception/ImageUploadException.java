package kr.kro.airbob.domain.image.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class ImageUploadException extends BaseException {
	public ImageUploadException(String fileName) {
		super(fileName, ErrorCode.IMAGE_UPLOAD_FAILED);
	}
}
