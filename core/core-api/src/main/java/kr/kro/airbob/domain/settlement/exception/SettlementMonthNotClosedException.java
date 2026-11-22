package kr.kro.airbob.domain.settlement.exception;

import kr.kro.airbob.core.support.error.BaseException;
import kr.kro.airbob.core.support.error.ErrorCode;

public class SettlementMonthNotClosedException extends BaseException {

	public SettlementMonthNotClosedException() {
		super(ErrorCode.SETTLEMENT_MONTH_NOT_CLOSED);
	}
}
