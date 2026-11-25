package kr.kro.airbob.core.domain.review;

import java.util.List;

import kr.kro.airbob.core.support.cursor.dto.CursorResponse;

/** 숙소의 게시된 리뷰 한 커서 페이지. */
public record ReviewPage(
	List<ReviewItem> reviews,
	CursorResponse.PageInfo pageInfo) {
}
