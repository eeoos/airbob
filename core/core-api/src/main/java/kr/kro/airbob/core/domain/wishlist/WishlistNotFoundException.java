package kr.kro.airbob.core.domain.wishlist;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class WishlistNotFoundException extends BaseException {

	public WishlistNotFoundException() {
		super(ErrorCode.WISHLIST_NOT_FOUND);
	}
}
