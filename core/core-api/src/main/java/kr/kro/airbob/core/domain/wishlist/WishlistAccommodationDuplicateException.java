package kr.kro.airbob.core.domain.wishlist;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class WishlistAccommodationDuplicateException extends BaseException {

	public WishlistAccommodationDuplicateException() {
		super(ErrorCode.WISHLIST_ACCOMMODATION_DUPLICATE);
	}
}
