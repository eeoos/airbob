package kr.kro.airbob.domain.accommodation.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.image.entity.AccommodationImage;
import kr.kro.airbob.domain.image.exception.EmptyImageFileException;
import kr.kro.airbob.domain.image.exception.ImageFileSizeExceededException;
import kr.kro.airbob.domain.image.exception.ImageNotFoundException;
import kr.kro.airbob.domain.image.exception.ImageUploadException;
import kr.kro.airbob.domain.image.exception.InvalidImageFormatException;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccommodationImageService {

	public static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;
	public static final String IMAGE_JPEG = "image/jpeg";
	public static final String IMAGE_PNG = "image/png";

	private final AccommodationImageRepository accommodationImageRepository;
	private final AccommodationRepository accommodationRepository;
	private final S3ImageUploader s3ImageUploader;
	private final AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;
	private final AccommodationSearchRefreshPublisher searchRefreshPublisher;

	@Transactional
	public ImageResponse.ImageUploadResult uploadImages(
		Long accommodationId,
		List<MultipartFile> images,
		Long memberId
	) {
		Accommodation accommodation = findByIdAndMemberIdExceptDeleted(accommodationId, memberId);

		List<ImageResponse.ImageInfo> imageInfos = new ArrayList<>();
		List<AccommodationImage> savedImages = new ArrayList<>();

		for (MultipartFile image : images) {
			validateImageFile(image);

			String imageUrl;
			try {
				String dirName = "accommodationInfos/" + accommodationId;
				imageUrl = s3ImageUploader.upload(image, dirName);
			} catch (IOException e) {
				log.error("이미지 업로드 실패: accommodationId={}, fileName={}", accommodation.getId(),
					image.getOriginalFilename(), e);
				throw new ImageUploadException(image.getOriginalFilename());
			}

			AccommodationImage accommodationImage = AccommodationImage.builder()
				.accommodation(accommodation)
				.imageUrl(imageUrl)
				.build();
			savedImages.add(accommodationImage);
		}

		List<AccommodationImage> actuallySavedImages = accommodationImageRepository.saveAll(savedImages);

		for (AccommodationImage savedImage : actuallySavedImages) {
			imageInfos.add(ImageResponse.ImageInfo.builder()
				.id(savedImage.getId())
				.imageUrl(savedImage.getImageUrl())
				.build());
		}

		boolean thumbnailChanged = findAndUpdateThumbnail(accommodation);
		publishReindexEventIfNeeded(accommodation, thumbnailChanged);
		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.IMAGE);

		return ImageResponse.ImageUploadResult.from(imageInfos);
	}

	@Transactional
	public void deleteImage(Long accommodationId, Long imageId, Long memberId) {
		Accommodation accommodation = findByIdAndMemberIdExceptDeleted(accommodationId, memberId);

		AccommodationImage image = accommodationImageRepository.findById(imageId)
			.orElseThrow(ImageNotFoundException::new);

		if (!image.getAccommodation().getId().equals(accommodationId)) {
			throw new InvalidInputException();
		}

		accommodationImageRepository.delete(image);

		boolean thumbnailChanged = image.getImageUrl().equals(accommodation.getThumbnailUrl())
			&& findAndUpdateThumbnail(accommodation);
		publishReindexEventIfNeeded(accommodation, thumbnailChanged);
		s3ImageUploader.delete(image.getImageUrl());

		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.IMAGE);
	}

	private void validateImageFile(MultipartFile file) {
		if (file.isEmpty()) {
			throw new EmptyImageFileException();
		}

		if (file.getSize() > MAX_IMAGE_SIZE) {
			throw new ImageFileSizeExceededException();
		}

		String contentType = file.getContentType();
		if (contentType == null || (!contentType.equals(IMAGE_JPEG) && !contentType.equals(IMAGE_PNG))) {
			throw new InvalidImageFormatException();
		}
	}

	private boolean findAndUpdateThumbnail(Accommodation accommodation) {
		List<AccommodationImage> remainingImages =
			accommodationImageRepository.findByAccommodationIdOrderByIdAsc(accommodation.getId());
		String newThumbnailUrl = remainingImages.isEmpty() ? null : remainingImages.getFirst().getImageUrl();

		if (!Objects.equals(accommodation.getThumbnailUrl(), newThumbnailUrl)) {
			accommodation.updateThumbnailUrl(newThumbnailUrl);
			return true;
		}
		return false;
	}

	private void publishReindexEventIfNeeded(
		Accommodation accommodation,
		boolean thumbnailChanged
	) {
		if (thumbnailChanged && accommodation.getStatus() == AccommodationStatus.PUBLISHED) {
			searchRefreshPublisher.requestRefresh(accommodation.getAccommodationUid());
		}
	}

	private Accommodation findByIdAndMemberIdExceptDeleted(Long accommodationId, Long memberId) {
		return accommodationRepository.findByIdAndMemberIdAndStatusNot(
			accommodationId, memberId, AccommodationStatus.DELETED
		).orElseThrow(AccommodationNotFoundException::new);
	}
}
