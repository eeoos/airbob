package kr.kro.airbob.domain.settlement.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class SettlementMonthNotClosedException extends BaseException {

	public SettlementMonthNotClosedException() {
		super(ErrorCode.SETTLEMENT_MONTH_NOT_CLOSED);
	}
}
