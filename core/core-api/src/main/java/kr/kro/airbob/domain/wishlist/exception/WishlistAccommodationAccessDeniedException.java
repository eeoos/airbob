package kr.kro.airbob.domain.wishlist.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class WishlistAccommodationAccessDeniedException extends BaseException {

	public WishlistAccommodationAccessDeniedException() {
		super(ErrorCode.WISHLIST_ACCOMMODATION_ACCESS_DENIED);
	}
}
