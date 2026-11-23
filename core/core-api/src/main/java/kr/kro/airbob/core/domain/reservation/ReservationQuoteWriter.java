package kr.kro.airbob.core.domain.reservation;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.reservation.repository.ReservationQuoteRepository;
import lombok.RequiredArgsConstructor;

/** 예약 견적 저장과 보존 기간 정리 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class ReservationQuoteWriter {

	private final ReservationQuoteRepository quoteRepository;

	public void create(ReservationQuote quote) {
		quoteRepository.save(quote);
	}

	/** created_at이 cutoff보다 오래된 견적을 최대 batchSize건 지우고 지운 건수를 반환한다. */
	public int deleteRetentionExpiredBatch(Instant cutoffExclusive, int batchSize) {
		List<Long> ids = quoteRepository.findRetentionExpiredIdsForCleanup(cutoffExclusive, batchSize);
		if (ids.isEmpty()) {
			return 0;
		}
		return quoteRepository.deleteCleanupBatchByIds(ids);
	}
}
