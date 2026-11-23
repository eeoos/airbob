package kr.kro.airbob.core.domain.settlement;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class SettlementAccessDeniedException extends BaseException {

	public SettlementAccessDeniedException() {
		super(ErrorCode.SETTLEMENT_ACCESS_DENIED);
	}
}
