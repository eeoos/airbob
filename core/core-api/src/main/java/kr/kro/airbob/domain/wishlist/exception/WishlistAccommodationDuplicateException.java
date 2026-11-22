package kr.kro.airbob.domain.wishlist.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class WishlistAccommodationDuplicateException extends BaseException {

	public WishlistAccommodationDuplicateException() {
		super(ErrorCode.WISHLIST_ACCOMMODATION_DUPLICATE);
	}
}
