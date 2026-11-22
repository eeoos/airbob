package kr.kro.airbob.core.domain.review;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.kro.airbob.core.domain.image.EmptyImageFileException;
import kr.kro.airbob.core.domain.image.ImageFileSizeExceededException;
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
public class ReviewImageService {

	public static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;
	public static final String IMAGE_JPEG = "image/jpeg";
	public static final String IMAGE_PNG = "image/png";

	private final ReviewReader reviewReader;
	private final ReviewImageReader reviewImageReader;
	private final ReviewImageWriter reviewImageWriter;
	private final S3ImageUploader s3ImageUploader;

	/** 이미지를 저장소에 올리고 리뷰에 붙인다. 이번 요청으로 추가된 이미지를 돌려준다. */
	@Transactional
	public List<StoredImage> uploadReviewImages(Long reviewId, List<MultipartFile> images, Long memberId) {

		Review review = reviewReader.readByAuthor(reviewId, memberId);
		images.forEach(this::validateImageFile);

		List<String> imageUrls = new ArrayList<>();

		for (MultipartFile image : images) {
			try {
				String dirName = "reviews/" + reviewId;
				imageUrls.add(s3ImageUploader.upload(image, dirName));
			} catch (IOException e) {
				log.error("리뷰 이미지 업로드 실패: reviewId={}, fileName={}", reviewId, image.getOriginalFilename(), e);
				throw new ImageUploadException(image.getOriginalFilename());
			}
		}

		return reviewImageWriter.addAll(review, imageUrls);
	}

	@Transactional
	public void deleteReviewImage(Long reviewId, Long imageId, Long memberId) {
		ReviewImage image = reviewImageReader.readByAuthor(imageId, memberId);

		if (!image.getReview().getId().equals(reviewId)) {
			throw new InvalidInputException();
		}

		s3ImageUploader.delete(image.getImageUrl());

		reviewImageWriter.delete(image);
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
}
