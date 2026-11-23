package kr.kro.airbob.core.domain.search;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.accommodation.AccommodationNotFoundException;
import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccommodationSearchSnapshotReader {

	private final AccommodationDocumentBuilder documentBuilder;

	@Transactional(readOnly = true)
	public Optional<AccommodationDocument> readPublished(UUID accommodationUid) {
		try {
			AccommodationDocument document =
				documentBuilder.buildAccommodationDocument(accommodationUid);
			if (!AccommodationStatus.PUBLISHED.name().equals(document.status())) {
				return Optional.empty();
			}
			return Optional.of(document);
		} catch (AccommodationNotFoundException ignored) {
			return Optional.empty();
		}
	}
}
