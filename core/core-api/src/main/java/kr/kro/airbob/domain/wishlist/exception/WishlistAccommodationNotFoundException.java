package kr.kro.airbob.domain.wishlist.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class WishlistAccommodationNotFoundException extends BaseException {

	public WishlistAccommodationNotFoundException() {
		super(ErrorCode.WISHLIST_ACCOMMODATION_NOT_FOUND);
	}
}
