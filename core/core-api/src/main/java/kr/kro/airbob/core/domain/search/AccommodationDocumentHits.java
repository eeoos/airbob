package kr.kro.airbob.core.domain.search;

import java.util.List;

/** 검색 엔진이 돌려준 한 페이지의 문서와 전체 건수. 전체 건수는 Elasticsearch 기본 상한(1만)을 넘지 않을 수 있다. */
public record AccommodationDocumentHits(
	List<AccommodationDocument> documents,
	long total) {

	public AccommodationDocumentHits {
		documents = List.copyOf(documents);
	}
}
