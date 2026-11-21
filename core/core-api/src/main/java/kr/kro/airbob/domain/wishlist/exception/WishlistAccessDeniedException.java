package kr.kro.airbob.domain.wishlist.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class WishlistAccessDeniedException extends BaseException {

	public WishlistAccessDeniedException() {
		super(ErrorCode.WISHLIST_ACCESS_DENIED);
	}
}
