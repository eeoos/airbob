package kr.kro.airbob.domain.accommodation.port;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;

/**
 * 숙소의 날짜별 재고를 현재 예약 가능 기간만큼 준비한다. reservation의 재고 seed가 구현하므로 accommodation은 reservation에
 * 의존하지 않는다. 게시와 시간대 변경의 비즈니스 트랜잭션 안에서 호출한다.
 */
public interface AccommodationInventorySeeder {

	void seedCurrentHorizon(Accommodation accommodation);
}
