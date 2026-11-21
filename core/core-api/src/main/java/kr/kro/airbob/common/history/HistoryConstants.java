package kr.kro.airbob.common.history;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HistoryConstants {

	// Master 이력의 현재 행 표시용 valid_to 센티넬
	public static final LocalDateTime FOREVER = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
}
