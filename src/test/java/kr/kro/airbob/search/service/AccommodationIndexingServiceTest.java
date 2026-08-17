package kr.kro.airbob.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.repository.AccommodationSearchRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 색인 서비스 테스트")
class AccommodationIndexingServiceTest {

	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");

	@Mock private AccommodationSearchRepository searchRepository;
	@Mock private AccommodationSearchSnapshotReader snapshotReader;

	@Test
	@DisplayName("DB 최신 스냅샷이 공개일 때 문서 전체를 덮어쓴다")
	void upsertsPublishedSnapshot() {
		AccommodationDocument document = AccommodationDocument.builder()
			.id(ACCOMMODATION_UID.toString())
			.status("PUBLISHED")
			.build();
		given(snapshotReader.readPublished(ACCOMMODATION_UID))
			.willReturn(Optional.of(document));
		AccommodationIndexingService service =
			new AccommodationIndexingService(searchRepository, snapshotReader);

		service.refreshAccommodationIndex(ACCOMMODATION_UID);

		then(searchRepository).should().save(document);
		then(searchRepository).should(never()).deleteById(ACCOMMODATION_UID.toString());
	}

	@Test
	@DisplayName("게시 중단과 삭제/누락은 모두 String UID 문서 삭제로 수렴한다")
	void deletesWhenCurrentSnapshotIsNotSearchable() {
		given(snapshotReader.readPublished(ACCOMMODATION_UID))
			.willReturn(Optional.empty());
		AccommodationIndexingService service =
			new AccommodationIndexingService(searchRepository, snapshotReader);

		service.refreshAccommodationIndex(ACCOMMODATION_UID);

		then(searchRepository).should().deleteById(ACCOMMODATION_UID.toString());
		then(searchRepository).should(never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("MySQL 스냅샷 트랜잭션은 ES 네트워크 I/O 전에 종료된다")
	void performsElasticsearchIoWithoutAnActiveDatabaseTransaction() throws Exception {
		AccommodationDocument document = AccommodationDocument.builder()
			.id(ACCOMMODATION_UID.toString())
			.status("PUBLISHED")
			.build();
		given(snapshotReader.readPublished(ACCOMMODATION_UID))
			.willReturn(Optional.of(document));
		given(searchRepository.save(document)).willAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return document;
		});
		AccommodationIndexingService service =
			new AccommodationIndexingService(searchRepository, snapshotReader);

		service.refreshAccommodationIndex(ACCOMMODATION_UID);

		assertThat(AccommodationIndexingService.class
			.getMethod("refreshAccommodationIndex", UUID.class)
			.getAnnotation(Transactional.class)).isNull();
		Transactional snapshotTransaction = AccommodationSearchSnapshotReader.class
			.getMethod("readPublished", UUID.class)
			.getAnnotation(Transactional.class);
		assertThat(snapshotTransaction).isNotNull();
		assertThat(snapshotTransaction.readOnly()).isTrue();
	}
}
