package kr.kro.airbob.core.api.controller.v1.search.response;

import kr.kro.airbob.core.domain.search.AccommodationSearchPage;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

@DisplayName("숙소 검색 응답 테스트")
class AccommodationSearchResponseTest {

	@Test
	@DisplayName("빈 결과도 요청 페이지 번호에 맞는 이전 페이지 정보를 반환한다")
	void emptyPageKeepsRequestedPageMetadata() {
		AccommodationSearchResponse.PageInfo pageInfo =
			AccommodationSearchResponse.PageInfo.from(AccommodationSearchPage.empty(PageRequest.of(5, 18)));

		assertThat(pageInfo.currentPage()).isEqualTo(5);
		assertThat(pageInfo.isFirst()).isFalse();
		assertThat(pageInfo.isLast()).isTrue();
		assertThat(pageInfo.hasPrevious()).isTrue();
		assertThat(pageInfo.hasNext()).isFalse();
	}
}
