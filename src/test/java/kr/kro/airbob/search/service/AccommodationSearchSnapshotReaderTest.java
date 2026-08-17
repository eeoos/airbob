package kr.kro.airbob.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.search.document.AccommodationDocument;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 검색 스냅샷 리더")
class AccommodationSearchSnapshotReaderTest {

	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");

	@Mock private AccommodationDocumentBuilder documentBuilder;
	@InjectMocks private AccommodationSearchSnapshotReader reader;

	@Test
	@DisplayName("현재 DB 상태가 공개일 때만 전체 문서를 반환한다")
	void returnsOnlyPublishedProjection() {
		AccommodationDocument published = AccommodationDocument.builder()
			.id(ACCOMMODATION_UID.toString())
			.status("PUBLISHED")
			.build();
		given(documentBuilder.buildAccommodationDocument(ACCOMMODATION_UID))
			.willReturn(published);

		assertThat(reader.readPublished(ACCOMMODATION_UID)).contains(published);
	}

	@Test
	@DisplayName("게시 중단과 삭제/누락은 동일하게 빈 스냅샷으로 수렴한다")
	void convergesUnpublishedAndMissingToEmptySnapshot() {
		AccommodationDocument unpublished = AccommodationDocument.builder()
			.id(ACCOMMODATION_UID.toString())
			.status("UNPUBLISHED")
			.build();
		given(documentBuilder.buildAccommodationDocument(ACCOMMODATION_UID))
			.willReturn(unpublished)
			.willThrow(new AccommodationNotFoundException());

		assertThat(reader.readPublished(ACCOMMODATION_UID)).isEmpty();
		assertThat(reader.readPublished(ACCOMMODATION_UID)).isEmpty();
	}
}
