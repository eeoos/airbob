package kr.kro.airbob.domain.wishlist.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class WishlistNotFoundException extends BaseException {

	public WishlistNotFoundException() {
		super(ErrorCode.WISHLIST_NOT_FOUND);
	}
}
