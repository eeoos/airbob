package kr.kro.airbob.domain.wishlist.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class WishlistAccommodationDuplicateException extends BaseException {

	public WishlistAccommodationDuplicateException() {
		super(ErrorCode.WISHLIST_ACCOMMODATION_DUPLICATE);
	}
}
