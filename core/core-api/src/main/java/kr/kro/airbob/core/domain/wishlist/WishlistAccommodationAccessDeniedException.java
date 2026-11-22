package kr.kro.airbob.core.domain.wishlist;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class WishlistAccommodationAccessDeniedException extends BaseException {

	public WishlistAccommodationAccessDeniedException() {
		super(ErrorCode.WISHLIST_ACCOMMODATION_ACCESS_DENIED);
	}
}
