package kr.kro.airbob.domain.wishlist.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class WishlistAccommodationAccessDeniedException extends BaseException {

	public WishlistAccommodationAccessDeniedException() {
		super(ErrorCode.WISHLIST_ACCOMMODATION_ACCESS_DENIED);
	}
}
