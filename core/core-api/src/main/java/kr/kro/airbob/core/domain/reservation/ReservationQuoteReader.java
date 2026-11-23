package kr.kro.airbob.core.domain.reservation;

import java.util.UUID;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.reservation.repository.ReservationQuoteRepository;
import lombok.RequiredArgsConstructor;

/** 예약 견적 조회 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class ReservationQuoteReader {

	private final ReservationQuoteRepository quoteRepository;

	/** 회원의 견적을 FOR UPDATE로 잠근다. 다른 회원의 견적은 없는 것으로 본다. */
	public ReservationQuote readOwnedForUpdate(UUID quoteUid, Long memberId) {
		return quoteRepository.findByQuoteUidAndMemberIdForUpdate(quoteUid, memberId)
			.orElseThrow(ReservationQuoteNotFoundException::new);
	}
}
