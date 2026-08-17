package kr.kro.airbob.cursor.util;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.cursor.dto.CursorData;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.dto.ReviewCursorData;

@ExtendWith(MockitoExtension.class)
@DisplayName("커서 페이지 정보 생성 테스트")
class CursorPageInfoCreatorTest {

	@Mock
	private CursorEncoder cursorEncoder;

	private CursorPageInfoCreator creator;

	@BeforeEach
	void setUp() {
		creator = new CursorPageInfoCreator(cursorEncoder);
	}

	@Test
	@DisplayName("다음 일반 페이지가 있으면 마지막 항목으로 커서를 만든다")
	void createsGenericNextCursorFromLastItem() {
		List<Item> content = List.of(
			item(1L, 10, 3),
			item(2L, 20, 4)
		);
		when(cursorEncoder.encode(any())).thenReturn("next-cursor");

		CursorResponse.PageInfo pageInfo = creator.createPageInfo(
			content,
			true,
			Item::id,
			Item::createdAt
		);

		ArgumentCaptor<CursorData> cursorCaptor = ArgumentCaptor.forClass(CursorData.class);
		verify(cursorEncoder).encode(cursorCaptor.capture());
		assertThat(cursorCaptor.getValue())
			.isEqualTo(new CursorData(2L, content.getLast().createdAt()));
		assertThat(pageInfo)
			.isEqualTo(new CursorResponse.PageInfo(true, "next-cursor", 2));
	}

	@Test
	@DisplayName("다음 리뷰 페이지가 있으면 평점을 포함한 커서를 만든다")
	void createsReviewNextCursorFromLastItem() {
		List<Item> content = List.of(
			item(1L, 10, 5),
			item(2L, 20, 4)
		);
		when(cursorEncoder.encode(any())).thenReturn("review-next-cursor");

		CursorResponse.PageInfo pageInfo = creator.createPageInfo(
			content,
			true,
			Item::id,
			Item::createdAt,
			Item::rating
		);

		ArgumentCaptor<ReviewCursorData> cursorCaptor =
			ArgumentCaptor.forClass(ReviewCursorData.class);
		verify(cursorEncoder).encode(cursorCaptor.capture());
		assertThat(cursorCaptor.getValue())
			.isEqualTo(new ReviewCursorData(2L, content.getLast().createdAt(), 4));
		assertThat(pageInfo)
			.isEqualTo(new CursorResponse.PageInfo(true, "review-next-cursor", 2));
	}

	@Test
	@DisplayName("다음 페이지가 없으면 커서를 만들지 않는다")
	void omitsCursorWhenThereIsNoNextPage() {
		CursorResponse.PageInfo pageInfo = creator.createPageInfo(
			List.of(item(1L, 10, 3)),
			false,
			Item::id,
			Item::createdAt
		);

		assertThat(pageInfo)
			.isEqualTo(new CursorResponse.PageInfo(false, null, 1));
		verifyNoInteractions(cursorEncoder);
	}

	@Test
	@DisplayName("빈 결과는 다음 페이지가 없는 크기 0으로 반환한다")
	void createsEmptyPageInfo() {
		CursorResponse.PageInfo pageInfo = creator.createPageInfo(
			List.<Item>of(),
			true,
			Item::id,
			Item::createdAt
		);

		assertThat(pageInfo)
			.isEqualTo(new CursorResponse.PageInfo(false, null, 0));
		verifyNoInteractions(cursorEncoder);
	}

	private Item item(long id, int minute, int rating) {
		return new Item(
			id,
			LocalDateTime.of(2026, 8, 13, 12, minute),
			rating
		);
	}

	private record Item(
		Long id,
		LocalDateTime createdAt,
		Integer rating
	) {
	}
}
