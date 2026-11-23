package kr.kro.airbob.core.domain.accommodation;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class AccommodationNotFoundException extends BaseException {

    public AccommodationNotFoundException() {
        super(ErrorCode.ACCOMMODATION_NOT_FOUND);
    }
}
