package kr.kro.airbob.domain.wishlist.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class WishlistNotFoundException extends BaseException {

	public WishlistNotFoundException() {
		super(ErrorCode.WISHLIST_NOT_FOUND);
	}
}
