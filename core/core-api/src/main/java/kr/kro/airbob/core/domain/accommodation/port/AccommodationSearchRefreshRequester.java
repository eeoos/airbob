package kr.kro.airbob.core.domain.accommodation.port;

import java.util.UUID;

/**
 * 숙소가 바뀌었을 때 검색 문서 갱신을 요청한다. search의 발행기가 구현하므로 accommodation은 search에 의존하지 않는다
 * (search는 문서를 만들려고 이미 accommodation에 의존한다). 비즈니스 트랜잭션 안에서 호출한다.
 */
public interface AccommodationSearchRefreshRequester {

	void requestRefresh(UUID accommodationUid);
}
