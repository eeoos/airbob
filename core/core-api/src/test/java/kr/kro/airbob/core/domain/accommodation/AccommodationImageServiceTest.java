package kr.kro.airbob.core.domain.accommodation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import kr.kro.airbob.core.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.core.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.core.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.core.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.core.domain.image.ImageStorageTransactionEvents.ImageDeletionRequested;
import kr.kro.airbob.core.domain.image.ImageStorageTransactionEvents.ImageUploaded;
import kr.kro.airbob.core.domain.image.ImageUploadException;
import kr.kro.airbob.core.domain.image.S3ImageUploader;
import kr.kro.airbob.core.domain.image.StoredImage;
import kr.kro.airbob.core.domain.search.messaging.AccommodationSearchRefreshPublisher;
import kr.kro.airbob.core.support.error.InvalidInputException;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 이미지 서비스 단위 테스트")
class AccommodationImageServiceTest {

	@Mock
	private AccommodationImageRepository accommodationImageRepository;
	@Mock
	private AccommodationRepository accommodationRepository;
	@Mock
	private S3ImageUploader s3ImageUploader;
	@Mock
	private ApplicationEventPublisher applicationEventPublisher;
	@Mock
	private AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;
	@Mock
	private AccommodationSearchRefreshPublisher searchRefreshPublisher;
	@Captor
	private ArgumentCaptor<List<AccommodationImage>> imagesCaptor;

	private AccommodationImageService accommodationImageService;

	@BeforeEach
	void setUp() {
		accommodationImageService = new AccommodationImageService(new AccommodationReader(accommodationRepository),
			new AccommodationImageReader(accommodationImageRepository),
			new AccommodationImageWriter(accommodationImageRepository),
			s3ImageUploader, applicationEventPublisher, cacheInvalidationPublisher, searchRefreshPublisher);
	}

	@Test
	@DisplayName("이미지를 저장하고 가장 먼저 저장된 이미지를 썸네일로 지정한다")
	void uploadImagesPersistsUrlsAndSetsFirstSavedImageAsThumbnail() throws IOException {
		Accommodation accommodation = accommodation(1L, null);
		MockMultipartFile firstFile = new MockMultipartFile(
			"images", "first.jpg", "image/jpeg", new byte[] {1});
		MockMultipartFile secondFile = new MockMultipartFile(
			"images", "second.png", "image/png", new byte[] {2});
		AccommodationImage savedFirst = image(10L, accommodation, "https://cdn.example.com/first.jpg");
		AccommodationImage savedSecond = image(11L, accommodation, "https://cdn.example.com/second.png");

		given(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			1L, 2L, AccommodationStatus.DELETED))
			.willReturn(Optional.of(accommodation));
		given(s3ImageUploader.upload(firstFile, "accommodationInfos/1"))
			.willReturn(savedFirst.getImageUrl());
		given(s3ImageUploader.upload(secondFile, "accommodationInfos/1"))
			.willReturn(savedSecond.getImageUrl());
		given(accommodationImageRepository.saveAll(anyList()))
			.willReturn(List.of(savedFirst, savedSecond));
		given(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.willReturn(List.of(savedFirst, savedSecond));

		List<StoredImage> response = accommodationImageService.uploadImages(
			1L, List.of(firstFile, secondFile), 2L);

		then(accommodationImageRepository).should().saveAll(imagesCaptor.capture());
		assertThat(imagesCaptor.getValue())
			.extracting(AccommodationImage::getImageUrl)
			.containsExactly(savedFirst.getImageUrl(), savedSecond.getImageUrl());
		assertThat(imagesCaptor.getValue())
			.extracting(AccommodationImage::getAccommodation)
			.containsOnly(accommodation);
		assertThat(response).containsExactly(
			new StoredImage(10L, savedFirst.getImageUrl()),
			new StoredImage(11L, savedSecond.getImageUrl()));
		assertThat(accommodation.getThumbnailUrl()).isEqualTo(savedFirst.getImageUrl());
		then(applicationEventPublisher).should().publishEvent(new ImageUploaded(savedFirst.getImageUrl()));
		then(applicationEventPublisher).should().publishEvent(new ImageUploaded(savedSecond.getImageUrl()));
		then(cacheInvalidationPublisher).should().publish(
			1L, AccommodationDetailCacheInvalidationReason.IMAGE);
	}

	@Test
	@DisplayName("여러 이미지 업로드 중 실패해도 이미 업로드된 객체의 롤백 정리를 등록한다")
	void registersRollbackCleanupBeforeLaterUploadFails() throws IOException {
		Accommodation accommodation = accommodation(1L, null);
		MockMultipartFile firstFile = new MockMultipartFile(
			"images", "first.jpg", "image/jpeg", new byte[] {1});
		MockMultipartFile secondFile = new MockMultipartFile(
			"images", "second.jpg", "image/jpeg", new byte[] {2});
		String firstImageUrl = "https://cdn.example.com/first.jpg";
		given(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			1L, 2L, AccommodationStatus.DELETED))
			.willReturn(Optional.of(accommodation));
		given(s3ImageUploader.upload(firstFile, "accommodationInfos/1"))
			.willReturn(firstImageUrl);
		given(s3ImageUploader.upload(secondFile, "accommodationInfos/1"))
			.willThrow(new IOException("S3 unavailable"));

		assertThatThrownBy(() -> accommodationImageService.uploadImages(
			1L, List.of(firstFile, secondFile), 2L))
			.isInstanceOf(ImageUploadException.class);

		then(applicationEventPublisher).should().publishEvent(new ImageUploaded(firstImageUrl));
		then(accommodationImageRepository).should(never()).saveAll(anyList());
		then(cacheInvalidationPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("현재 썸네일 이미지를 삭제하면 남은 첫 이미지로 썸네일을 교체한다")
	void deleteCurrentThumbnailSelectsNextImage() {
		Accommodation accommodation = accommodation(1L, "https://cdn.example.com/first.jpg");
		AccommodationImage currentThumbnail = image(
			10L, accommodation, "https://cdn.example.com/first.jpg");
		AccommodationImage nextImage = image(11L, accommodation, "https://cdn.example.com/second.jpg");

		givenOwnedAccommodationAndImage(accommodation, currentThumbnail);
		given(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.willReturn(List.of(nextImage));

		accommodationImageService.deleteImage(1L, 10L, 2L);

		then(applicationEventPublisher).should().publishEvent(
			new ImageDeletionRequested(currentThumbnail.getImageUrl()));
		then(s3ImageUploader).should(never()).delete(anyString());
		then(accommodationImageRepository).should().delete(currentThumbnail);
		assertThat(accommodation.getThumbnailUrl()).isEqualTo(nextImage.getImageUrl());
		then(cacheInvalidationPublisher).should().publish(
			1L, AccommodationDetailCacheInvalidationReason.IMAGE);
	}

	@Test
	@DisplayName("마지막 썸네일 이미지를 삭제하면 썸네일을 비운다")
	void deleteLastThumbnailClearsThumbnail() {
		Accommodation accommodation = accommodation(1L, "https://cdn.example.com/only.jpg");
		AccommodationImage currentThumbnail = image(
			10L, accommodation, "https://cdn.example.com/only.jpg");

		givenOwnedAccommodationAndImage(accommodation, currentThumbnail);
		given(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.willReturn(List.of());

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
		given(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			1L, 2L, AccommodationStatus.DELETED)).willReturn(Optional.of(accommodation));
		given(s3ImageUploader.upload(file, "accommodationInfos/1"))
			.willReturn(saved.getImageUrl());
		given(accommodationImageRepository.saveAll(anyList())).willReturn(List.of(saved));
		given(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.willReturn(List.of(saved));

		accommodationImageService.uploadImages(1L, List.of(file), 2L);

		then(searchRefreshPublisher).should().requestRefresh(accommodationUid);
	}

	@Test
	@DisplayName("초안 숙소의 썸네일 변경은 게시 이벤트 전까지 색인 이벤트를 만들지 않는다")
	void doesNotPublishReindexEventForDraftThumbnail() throws IOException {
		Accommodation accommodation = accommodation(1L, null);
		MockMultipartFile file = new MockMultipartFile(
			"images", "first.jpg", "image/jpeg", new byte[] {1});
		AccommodationImage saved = image(10L, accommodation, "https://cdn.example.com/first.jpg");
		given(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			1L, 2L, AccommodationStatus.DELETED)).willReturn(Optional.of(accommodation));
		given(s3ImageUploader.upload(file, "accommodationInfos/1"))
			.willReturn(saved.getImageUrl());
		given(accommodationImageRepository.saveAll(anyList())).willReturn(List.of(saved));
		given(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.willReturn(List.of(saved));

		accommodationImageService.uploadImages(1L, List.of(file), 2L);

		then(searchRefreshPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("게시된 숙소의 현재 썸네일을 삭제해도 재색인 이벤트를 저장한다")
	void publishesReindexEventWhenDeletingPublishedThumbnail() {
		UUID accommodationUid = UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");
		Accommodation accommodation = accommodation(
			1L,
			accommodationUid,
			AccommodationStatus.PUBLISHED,
			"https://cdn.example.com/first.jpg");
		AccommodationImage current = image(
			10L, accommodation, "https://cdn.example.com/first.jpg");
		AccommodationImage next = image(
			11L, accommodation, "https://cdn.example.com/second.jpg");
		givenOwnedAccommodationAndImage(accommodation, current);
		given(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(1L))
			.willReturn(List.of(next));

		accommodationImageService.deleteImage(1L, 10L, 2L);

		var order = inOrder(searchRefreshPublisher, applicationEventPublisher);
		then(searchRefreshPublisher).should(order).requestRefresh(accommodationUid);
		then(applicationEventPublisher).should(order).publishEvent(
			new ImageDeletionRequested(current.getImageUrl()));
	}

	@Test
	@DisplayName("다른 숙소의 이미지 ID로는 이미지를 삭제할 수 없다")
	void rejectImageBelongingToAnotherAccommodation() {
		Accommodation requestedAccommodation = accommodation(1L, null);
		Accommodation otherAccommodation = accommodation(3L, null);
		AccommodationImage otherImage = image(10L, otherAccommodation, "https://cdn.example.com/other.jpg");

		given(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			1L, 2L, AccommodationStatus.DELETED))
			.willReturn(Optional.of(requestedAccommodation));
		given(accommodationImageRepository.findById(10L)).willReturn(Optional.of(otherImage));

		assertThatThrownBy(() -> accommodationImageService.deleteImage(1L, 10L, 2L))
			.isInstanceOf(InvalidInputException.class);

		then(s3ImageUploader).shouldHaveNoInteractions();
		then(accommodationImageRepository).should(never()).delete(any());
		then(accommodationImageRepository).should(never()).findByAccommodationIdOrderByIdAsc(anyLong());
	}

	private void givenOwnedAccommodationAndImage(
		Accommodation accommodation,
		AccommodationImage image) {
		given(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			accommodation.getId(), 2L, AccommodationStatus.DELETED))
			.willReturn(Optional.of(accommodation));
		given(accommodationImageRepository.findById(image.getId())).willReturn(Optional.of(image));
	}

	private Accommodation accommodation(Long id, String thumbnailUrl) {
		return accommodation(id, UUID.randomUUID(), AccommodationStatus.DRAFT, thumbnailUrl);
	}

	private Accommodation accommodation(
		Long id,
		UUID accommodationUid,
		AccommodationStatus status,
		String thumbnailUrl) {
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
