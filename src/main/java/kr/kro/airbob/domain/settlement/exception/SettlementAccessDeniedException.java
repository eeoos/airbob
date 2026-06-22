package kr.kro.airbob.domain.settlement.exception;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

public class SettlementAccessDeniedException extends BaseException {

	public SettlementAccessDeniedException() {
		super(ErrorCode.SETTLEMENT_ACCESS_DENIED);
	}
}
