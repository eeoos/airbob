package kr.kro.airbob.domain.wishlist.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class WishlistAccessDeniedException extends BaseException {

	public WishlistAccessDeniedException() {
		super(ErrorCode.WISHLIST_ACCESS_DENIED);
	}
}
