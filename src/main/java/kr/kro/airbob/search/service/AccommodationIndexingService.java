package kr.kro.airbob.search.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccommodationIndexingService {

	private final AccommodationSearchRepository searchRepository;
	private final AccommodationDocumentBuilder documentBuilder;

	@Transactional(readOnly = true)
	public void refreshAccommodationIndex(UUID accommodationUid) {
		AccommodationDocument document =
			documentBuilder.buildAccommodationDocument(accommodationUid.toString());
		searchRepository.save(document);
		log.info("[ES-INDEX] 숙소 최신 상태 반영: {}", accommodationUid);
	}

	public void deleteAccommodationIndex(UUID accommodationUid) {
		searchRepository.deleteById(accommodationUid);
		log.info("[ES-INDEX] 숙소 삭제: {}", accommodationUid);
	}
}
