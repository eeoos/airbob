package kr.kro.airbob.core.domain.accommodation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.kro.airbob.core.domain.accommodation.cache.AccommodationDetailCacheInvalidationReason;
import kr.kro.airbob.core.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.core.domain.accommodation.port.AccommodationSearchRefreshRequester;
import kr.kro.airbob.core.domain.image.EmptyImageFileException;
import kr.kro.airbob.core.domain.image.ImageFileSizeExceededException;
import kr.kro.airbob.core.domain.image.ImageStorageTransactionEvents.ImageDeletionRequested;
import kr.kro.airbob.core.domain.image.ImageStorageTransactionEvents.ImageUploaded;
import kr.kro.airbob.core.domain.image.ImageUploadException;
import kr.kro.airbob.core.domain.image.InvalidImageFormatException;
import kr.kro.airbob.core.domain.image.S3ImageUploader;
import kr.kro.airbob.core.domain.image.StoredImage;
import kr.kro.airbob.core.support.error.InvalidInputException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccommodationImageService {

	public static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;
	public static final String IMAGE_JPEG = "image/jpeg";
	public static final String IMAGE_PNG = "image/png";

	private final AccommodationReader accommodationReader;
	private final AccommodationImageReader accommodationImageReader;
	private final AccommodationImageWriter accommodationImageWriter;
	private final S3ImageUploader s3ImageUploader;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;
	private final AccommodationSearchRefreshRequester searchRefreshPublisher;

	/** 이미지를 저장소에 올리고 숙소에 붙인다. 이번 요청으로 추가된 이미지를 돌려준다. */
	@Transactional
	public List<StoredImage> uploadImages(
		Long accommodationId,
		List<MultipartFile> images,
		Long memberId
	) {
		Accommodation accommodation = accommodationReader.readOwned(accommodationId, memberId);

		List<String> imageUrls = new ArrayList<>();

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
			// 이후 DB 저장이나 outbox 발행이 실패하면 방금 업로드한 객체를 롤백 시 정리한다.
			applicationEventPublisher.publishEvent(new ImageUploaded(imageUrl));

			imageUrls.add(imageUrl);
		}

		List<StoredImage> uploadedImages = accommodationImageWriter.addAll(accommodation, imageUrls);

		boolean thumbnailChanged = accommodationImageWriter.refreshThumbnail(accommodation);
		publishReindexEventIfNeeded(accommodation, thumbnailChanged);
		cacheInvalidationPublisher.publish(
			accommodationId, AccommodationDetailCacheInvalidationReason.IMAGE);

		return uploadedImages;
	}

	@Transactional
	public void deleteImage(Long accommodationId, Long imageId, Long memberId) {
		Accommodation accommodation = accommodationReader.readOwned(accommodationId, memberId);

		AccommodationImage image = accommodationImageReader.readById(imageId);

		if (!image.getAccommodation().getId().equals(accommodationId)) {
			throw new InvalidInputException();
		}

		accommodationImageWriter.delete(image);

		boolean thumbnailChanged = image.getImageUrl().equals(accommodation.getThumbnailUrl())
			&& accommodationImageWriter.refreshThumbnail(accommodation);
		publishReindexEventIfNeeded(accommodation, thumbnailChanged);
		// DB 롤백 시 원본 객체가 유실되지 않도록 실제 삭제는 커밋 이후로 미룬다.
		applicationEventPublisher.publishEvent(new ImageDeletionRequested(image.getImageUrl()));

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

	private void publishReindexEventIfNeeded(
		Accommodation accommodation,
		boolean thumbnailChanged
	) {
		if (thumbnailChanged && accommodation.getStatus() == AccommodationStatus.PUBLISHED) {
			searchRefreshPublisher.requestRefresh(accommodation.getAccommodationUid());
		}
	}
}
