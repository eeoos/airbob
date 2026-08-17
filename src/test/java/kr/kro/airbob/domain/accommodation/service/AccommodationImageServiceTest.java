package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.image.entity.AccommodationImage;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.search.event.AccommodationIndexingEvents.AccommodationUpdatedEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 이미지 서비스 단위 테스트")
class AccommodationImageServiceTest {

	@Mock private AccommodationImageRepository accommodationImageRepository;
	@Mock private AccommodationRepository accommodationRepository;
	@Mock private S3ImageUploader s3ImageUploader;
	@Mock private AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;
	@Mock private OutboxEventPublisher outboxEventPublisher;
	@Captor private ArgumentCaptor<List<AccommodationImage>> imagesCaptor;

	@InjectMocks
	private AccommodationImageService accommodationImageService;

	@Test
	@DisplayName("이미지를 저장하고 가장 먼저 저장된 이미지를 썸네일로 지정한다")
	void uploadImagesPersistsUrlsAndSetsFirstSavedImageAsThumbnail() throws IOException {
		Accommodation accommodation = accommodation(1L, null);
		MockMultipartFile firstFile = new MockMultipartFile(
			"images", "first.jpg", "image/jpeg", new byte[] {1}
		);
		MockMultipartFile secondFile = new MockMultipartFile(
			"images", "second.png", "image/png", new byte[] {2}
		);
		AccommodationImage savedFirst = image(10L, accommodation, "https://cdn.example.com/first.jpg");
		AccommodationImage savedSecond = image(11L, accommodation, "https://cdn.example.com/second.png");

		when(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(s3ImageUploader.upload(firstFile, "accommodationInfos/1"))
			.thenReturn(savedFirst.getImageUrl());
		when(s3ImageUploader.upload(secondFile, "accommodationInfos/1"))
			.thenReturn(savedSecond.getImageUrl());
		when(accommodationImageRepository.saveAll(anyList()))
			.thenReturn(List.of(savedFirst, savedSecond));
		when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.thenReturn(List.of(savedFirst, savedSecond));

		ImageResponse.ImageUploadResult response = accommodationImageService.uploadImages(
			1L, List.of(firstFile, secondFile), 2L
		);

		verify(accommodationImageRepository).saveAll(imagesCaptor.capture());
		assertThat(imagesCaptor.getValue())
			.extracting(AccommodationImage::getImageUrl)
			.containsExactly(savedFirst.getImageUrl(), savedSecond.getImageUrl());
		assertThat(imagesCaptor.getValue())
			.extracting(AccommodationImage::getAccommodation)
			.containsOnly(accommodation);
		assertThat(response.uploadedImages()).containsExactly(
			new ImageResponse.ImageInfo(10L, savedFirst.getImageUrl()),
			new ImageResponse.ImageInfo(11L, savedSecond.getImageUrl())
		);
		assertThat(accommodation.getThumbnailUrl()).isEqualTo(savedFirst.getImageUrl());
		verify(cacheInvalidationPublisher).publish(
			1L, AccommodationDetailCacheInvalidationReason.IMAGE);
	}

	@Test
	@DisplayName("현재 썸네일 이미지를 삭제하면 남은 첫 이미지로 썸네일을 교체한다")
	void deleteCurrentThumbnailSelectsNextImage() {
		Accommodation accommodation = accommodation(1L, "https://cdn.example.com/first.jpg");
		AccommodationImage currentThumbnail = image(
			10L, accommodation, "https://cdn.example.com/first.jpg"
		);
		AccommodationImage nextImage = image(11L, accommodation, "https://cdn.example.com/second.jpg");

		givenOwnedAccommodationAndImage(accommodation, currentThumbnail);
		when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.thenReturn(List.of(nextImage));

		accommodationImageService.deleteImage(1L, 10L, 2L);

		verify(s3ImageUploader).delete(currentThumbnail.getImageUrl());
		verify(accommodationImageRepository).delete(currentThumbnail);
		assertThat(accommodation.getThumbnailUrl()).isEqualTo(nextImage.getImageUrl());
		verify(cacheInvalidationPublisher).publish(
			1L, AccommodationDetailCacheInvalidationReason.IMAGE);
	}

	@Test
	@DisplayName("마지막 썸네일 이미지를 삭제하면 썸네일을 비운다")
	void deleteLastThumbnailClearsThumbnail() {
		Accommodation accommodation = accommodation(1L, "https://cdn.example.com/only.jpg");
		AccommodationImage currentThumbnail = image(
			10L, accommodation, "https://cdn.example.com/only.jpg"
		);

		givenOwnedAccommodationAndImage(accommodation, currentThumbnail);
		when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.thenReturn(List.of());

		accommodationImageService.deleteImage(1L, 10L, 2L);

		assertThat(accommodation.getThumbnailUrl()).isNull();
	}

	@Test
	@DisplayName("게시된 숙소의 썸네일이 바뀌면 같은 트랜잭션에 재색인 이벤트를 저장한다")
	void publishesReindexEventWhenPublishedThumbnailChanges() throws IOException {
		UUID accommodationUid = UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");
		Accommodation accommodation = accommodation(
			1L, accommodationUid, AccommodationStatus.PUBLISHED, null);
		MockMultipartFile file = new MockMultipartFile(
			"images", "first.jpg", "image/jpeg", new byte[] {1});
		AccommodationImage saved = image(10L, accommodation, "https://cdn.example.com/first.jpg");
		when(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			1L, 2L, AccommodationStatus.DELETED)).thenReturn(Optional.of(accommodation));
		when(s3ImageUploader.upload(file, "accommodationInfos/1"))
			.thenReturn(saved.getImageUrl());
		when(accommodationImageRepository.saveAll(anyList())).thenReturn(List.of(saved));
		when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.thenReturn(List.of(saved));

		accommodationImageService.uploadImages(1L, List.of(file), 2L);

		verify(outboxEventPublisher).save(
			EventType.ACCOMMODATION_UPDATED,
			new AccommodationUpdatedEvent(accommodationUid.toString()));
	}

	@Test
	@DisplayName("초안 숙소의 썸네일 변경은 게시 이벤트 전까지 색인 이벤트를 만들지 않는다")
	void doesNotPublishReindexEventForDraftThumbnail() throws IOException {
		Accommodation accommodation = accommodation(1L, null);
		MockMultipartFile file = new MockMultipartFile(
			"images", "first.jpg", "image/jpeg", new byte[] {1});
		AccommodationImage saved = image(10L, accommodation, "https://cdn.example.com/first.jpg");
		when(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			1L, 2L, AccommodationStatus.DELETED)).thenReturn(Optional.of(accommodation));
		when(s3ImageUploader.upload(file, "accommodationInfos/1"))
			.thenReturn(saved.getImageUrl());
		when(accommodationImageRepository.saveAll(anyList())).thenReturn(List.of(saved));
		when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.thenReturn(List.of(saved));

		accommodationImageService.uploadImages(1L, List.of(file), 2L);

		verifyNoInteractions(outboxEventPublisher);
	}

	@Test
	@DisplayName("게시된 숙소의 현재 썸네일을 삭제해도 재색인 이벤트를 저장한다")
	void publishesReindexEventWhenDeletingPublishedThumbnail() {
		UUID accommodationUid = UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");
		Accommodation accommodation = accommodation(
			1L,
			accommodationUid,
			AccommodationStatus.PUBLISHED,
			"https://cdn.example.com/first.jpg"
		);
		AccommodationImage current = image(
			10L, accommodation, "https://cdn.example.com/first.jpg");
		AccommodationImage next = image(
			11L, accommodation, "https://cdn.example.com/second.jpg");
		givenOwnedAccommodationAndImage(accommodation, current);
		when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.thenReturn(List.of(next));

		accommodationImageService.deleteImage(1L, 10L, 2L);

		var order = inOrder(outboxEventPublisher, s3ImageUploader);
		order.verify(outboxEventPublisher).save(
			EventType.ACCOMMODATION_UPDATED,
			new AccommodationUpdatedEvent(accommodationUid.toString()));
		order.verify(s3ImageUploader).delete(current.getImageUrl());
	}

	@Test
	@DisplayName("다른 숙소의 이미지 ID로는 이미지를 삭제할 수 없다")
	void rejectImageBelongingToAnotherAccommodation() {
		Accommodation requestedAccommodation = accommodation(1L, null);
		Accommodation otherAccommodation = accommodation(3L, null);
		AccommodationImage otherImage = image(10L, otherAccommodation, "https://cdn.example.com/other.jpg");

		when(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			1L, 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(requestedAccommodation));
		when(accommodationImageRepository.findById(10L)).thenReturn(Optional.of(otherImage));

		assertThatThrownBy(() -> accommodationImageService.deleteImage(1L, 10L, 2L))
			.isInstanceOf(InvalidInputException.class);

		verifyNoInteractions(s3ImageUploader);
		verify(accommodationImageRepository, never()).delete(any());
		verify(accommodationImageRepository, never()).findByAccommodationIdOrderByIdAsc(anyLong());
	}

	private void givenOwnedAccommodationAndImage(
		Accommodation accommodation,
		AccommodationImage image
	) {
		when(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			accommodation.getId(), 2L, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(accommodationImageRepository.findById(image.getId())).thenReturn(Optional.of(image));
	}

	private Accommodation accommodation(Long id, String thumbnailUrl) {
		return accommodation(id, UUID.randomUUID(), AccommodationStatus.DRAFT, thumbnailUrl);
	}

	private Accommodation accommodation(
		Long id,
		UUID accommodationUid,
		AccommodationStatus status,
		String thumbnailUrl
	) {
		return Accommodation.builder()
			.id(id)
			.accommodationUid(accommodationUid)
			.status(status)
			.thumbnailUrl(thumbnailUrl)
			.build();
	}

	private AccommodationImage image(Long id, Accommodation accommodation, String imageUrl) {
		return AccommodationImage.builder()
			.id(id)
			.accommodation(accommodation)
			.imageUrl(imageUrl)
			.build();
	}
}
