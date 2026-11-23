package kr.kro.airbob.search.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;
import lombok.RequiredArgsConstructor;

/** 숙소 검색 문서 저장·삭제 구현 컴포넌트. 문서 ID는 숙소 UID 문자열이다. */
@Component
@RequiredArgsConstructor
public class AccommodationDocumentWriter {

	private final AccommodationSearchRepository searchRepository;

	public void save(AccommodationDocument document) {
		searchRepository.save(document);
	}

	public void delete(UUID accommodationUid) {
		searchRepository.deleteById(accommodationUid.toString());
	}
}
