package kr.kro.airbob.common.history;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// Master 성격(상태가 기간 동안 유지, 시점 조회 빈번) 이력의 유효기간.
// 현재 행은 valid_to = FOREVER 센티넬. 변경 시 직전 행을 close()로 닫고 새 스냅샷을 INSERT (SCD Type-2)
@Getter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class MasterHistoryBase extends HistoryBase {

	@Column(nullable = false)
	private LocalDateTime validFrom;

	@Column(nullable = false)
	private LocalDateTime validTo;

	// 직전 현재 행을 주어진 시각에 닫는다.
	public void close(LocalDateTime closedAt) {
		this.validTo = closedAt;
	}
}
