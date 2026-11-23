package kr.kro.airbob.core.domain.search;

import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccommodationIndexingService {

	private final AccommodationDocumentWriter documentWriter;
	private final AccommodationSearchSnapshotReader snapshotReader;

	public void refreshAccommodationIndex(UUID accommodationUid) {
		var snapshot = snapshotReader.readPublished(accommodationUid);
		if (snapshot.isPresent()) {
			AccommodationDocument document = snapshot.get();
			documentWriter.save(document);
			log.info("[ES-INDEX] 숙소 최신 상태 반영: {}", accommodationUid);
			return;
		}
		documentWriter.delete(accommodationUid);
		log.info("[ES-INDEX] 검색 대상이 아닌 숙소 삭제: {}", accommodationUid);
	}
}
