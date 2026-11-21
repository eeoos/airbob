package kr.kro.airbob.domain.review.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.image.entity.ReviewImage;
import kr.kro.airbob.domain.image.exception.EmptyImageFileException;
import kr.kro.airbob.domain.image.exception.ImageFileSizeExceededException;
import kr.kro.airbob.domain.image.exception.ImageUploadException;
import kr.kro.airbob.domain.image.exception.InvalidImageFormatException;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.domain.review.entity.Review;
import kr.kro.airbob.domain.review.exception.ReviewAccessDeniedException;
import kr.kro.airbob.domain.review.exception.ReviewNotFoundException;
import kr.kro.airbob.domain.review.repository.ReviewImageRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewImageService {

	public static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;
	public static final String IMAGE_JPEG = "image/jpeg";
	public static final String IMAGE_PNG = "image/png";

	private final ReviewRepository reviewRepository;
	private final ReviewImageRepository reviewImageRepository;
	private final S3ImageUploader s3ImageUploader;

	@Transactional
	public ImageResponse.ImageUploadResult uploadReviewImages(Long reviewId, List<MultipartFile> images,
		Long memberId) {

		Review review = findReviewByIdAndAuthorId(reviewId, memberId);
		images.forEach(this::validateImageFile);

		List<ImageResponse.ImageInfo> imageInfos = new ArrayList<>();
		List<ReviewImage> savedImages = new ArrayList<>();

		for (MultipartFile image : images) {
			String imageUrl;
			try {
				String dirName = "reviews/" + reviewId;
				imageUrl = s3ImageUploader.upload(image, dirName);
			} catch (IOException e) {
				log.error("리뷰 이미지 업로드 실패: reviewId={}, fileName={}", reviewId, image.getOriginalFilename(), e);
				throw new ImageUploadException(image.getOriginalFilename());
			}

			ReviewImage reviewImage = ReviewImage.builder()
				.review(review)
				.imageUrl(imageUrl)
				.build();

			savedImages.add(reviewImage);
		}

		List<ReviewImage> actuallySavedImages = reviewImageRepository.saveAll(savedImages);

		for (ReviewImage savedImage : actuallySavedImages) {
			imageInfos.add(ImageResponse.ImageInfo.from(savedImage));
		}

		return ImageResponse.ImageUploadResult.from(imageInfos);
	}

	@Transactional
	public void deleteReviewImage(Long reviewId, Long imageId, Long memberId) {
		ReviewImage image = reviewImageRepository.findByIdAndReviewAuthorId(imageId, memberId)
			.orElseThrow(ReviewAccessDeniedException::new);

		if (!image.getReview().getId().equals(reviewId)) {
			throw new InvalidInputException();
		}

		s3ImageUploader.delete(image.getImageUrl());

		reviewImageRepository.delete(image);
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

	private Review findReviewByIdAndAuthorId(Long reviewId, Long memberId) {
		return reviewRepository.findByIdAndAuthorId(reviewId, memberId).orElseThrow(ReviewNotFoundException::new);
	}
}
