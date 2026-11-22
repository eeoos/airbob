package kr.kro.airbob.domain.wishlist.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class WishlistAccommodationNotFoundException extends BaseException {

	public WishlistAccommodationNotFoundException() {
		super(ErrorCode.WISHLIST_ACCOMMODATION_NOT_FOUND);
	}
}
