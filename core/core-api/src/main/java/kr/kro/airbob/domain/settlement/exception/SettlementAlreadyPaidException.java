package kr.kro.airbob.domain.settlement.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class SettlementAlreadyPaidException extends BaseException {

	public SettlementAlreadyPaidException() {
		super(ErrorCode.SETTLEMENT_ALREADY_PAID);
	}
}
