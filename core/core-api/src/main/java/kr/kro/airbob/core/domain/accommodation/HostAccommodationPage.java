package kr.kro.airbob.core.domain.accommodation;

import java.util.List;

import kr.kro.airbob.core.support.cursor.dto.CursorResponse;

/** 호스트 숙소 목록 한 커서 페이지. 최신순이다. */
public record HostAccommodationPage(
	List<HostAccommodationProjection> accommodations,
	CursorResponse.PageInfo pageInfo) {
}
