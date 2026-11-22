package kr.kro.airbob.domain.settlement.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class SettlementNotFoundException extends BaseException {

	public SettlementNotFoundException() {
		super(ErrorCode.SETTLEMENT_NOT_FOUND);
	}
}
