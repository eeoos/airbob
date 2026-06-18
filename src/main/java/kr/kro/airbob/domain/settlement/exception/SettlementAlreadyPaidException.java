package kr.kro.airbob.domain.settlement.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class SettlementAlreadyPaidException extends BaseException {

	public SettlementAlreadyPaidException() {
		super(ErrorCode.SETTLEMENT_ALREADY_PAID);
	}
}
