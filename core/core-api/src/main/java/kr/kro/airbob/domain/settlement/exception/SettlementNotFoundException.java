package kr.kro.airbob.domain.settlement.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class SettlementNotFoundException extends BaseException {

	public SettlementNotFoundException() {
		super(ErrorCode.SETTLEMENT_NOT_FOUND);
	}
}
