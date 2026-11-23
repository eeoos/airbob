package kr.kro.airbob.core.domain.search;

/** 검색 결과 숙소 하나. 색인 문서와 회원의 찜 여부를 담는다. 비회원이면 wishlisted는 false다. */
public record AccommodationSearchItem(
	AccommodationDocument document,
	boolean wishlisted
) {
}
