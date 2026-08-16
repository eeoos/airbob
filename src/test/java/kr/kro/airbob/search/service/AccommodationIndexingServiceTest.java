package kr.kro.airbob.search.service;

import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 색인 서비스 테스트")
class AccommodationIndexingServiceTest {

	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");

	@Mock private AccommodationSearchRepository searchRepository;
	@Mock private AccommodationDocumentBuilder documentBuilder;

	@Test
	@DisplayName("모든 변경 이벤트는 MySQL 최신 스냅샷으로 문서 전체를 덮어쓴다")
	void refreshesWholeDocumentFromAuthoritativeDatabaseState() {
		AccommodationIndexingService service =
			new AccommodationIndexingService(searchRepository, documentBuilder);
		AccommodationDocument document = AccommodationDocument.builder()
			.id(ACCOMMODATION_UID.toString())
			.status("PUBLISHED")
			.build();
		willReturn(document).given(documentBuilder)
			.buildAccommodationDocument(ACCOMMODATION_UID.toString());

		service.refreshAccommodationIndex(ACCOMMODATION_UID);

		then(searchRepository).should().save(document);
	}

	@Test
	@DisplayName("게시 중단 이벤트는 MySQL 최신 상태를 확인하고 검색 문서를 제거한다")
	void removesDocumentWhenAuthoritativeStateIsUnpublished() {
		AccommodationIndexingService service =
			new AccommodationIndexingService(searchRepository, documentBuilder);
		AccommodationDocument document = AccommodationDocument.builder()
			.id(ACCOMMODATION_UID.toString())
			.status("UNPUBLISHED")
			.build();
		willReturn(document).given(documentBuilder)
			.buildAccommodationDocument(ACCOMMODATION_UID.toString());

		service.refreshAccommodationIndex(ACCOMMODATION_UID);

		then(searchRepository).should().deleteById(ACCOMMODATION_UID);
		then(searchRepository).should(never()).save(document);
	}

	@Test
	@DisplayName("삭제 이벤트는 같은 UID가 반복되어도 동일 문서 삭제로 수렴한다")
	void deletesByStableAccommodationUid() {
		AccommodationIndexingService service =
			new AccommodationIndexingService(searchRepository, documentBuilder);

		service.deleteAccommodationIndex(ACCOMMODATION_UID);

		then(searchRepository).should().deleteById(ACCOMMODATION_UID);
	}
}
