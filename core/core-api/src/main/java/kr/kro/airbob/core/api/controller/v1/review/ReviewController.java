package kr.kro.airbob.core.api.controller.v1.review;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import kr.kro.airbob.core.api.controller.v1.image.response.ImageResponse;
import kr.kro.airbob.core.api.controller.v1.review.request.ReviewRequest;
import kr.kro.airbob.core.api.controller.v1.review.response.ReviewResponse;
import kr.kro.airbob.core.domain.image.StoredImage;
import kr.kro.airbob.core.domain.review.ReviewImageService;
import kr.kro.airbob.core.domain.review.ReviewService;
import kr.kro.airbob.core.domain.review.ReviewSortType;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReviewController implements ReviewApi {

	private final ReviewService reviewService;
	private final ReviewImageService reviewImageService;

	@Override
	public ResponseEntity<ApiResponse<ReviewResponse.Create>> createReview(
		Long accommodationId,
		ReviewRequest.Create request,
		Long memberId) {
		Long reviewId = reviewService.createReview(accommodationId, request.rating(), request.content(), memberId);
		ReviewResponse.Create response = new ReviewResponse.Create(reviewId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<ReviewResponse.Update>> updateReview(
		Long reviewId,
		ReviewRequest.Update request,
		Long memberId) {
		ReviewResponse.Update response = new ReviewResponse.Update(
			reviewService.updateReviewContent(reviewId, request.content(), request.rating(), memberId));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> deleteReview(
		Long reviewId,
		Long memberId) {
		reviewService.deleteReview(reviewId, memberId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@Override
	public ResponseEntity<ApiResponse<ImageResponse.ImageUploadResult>> uploadReviewImages(
		Long reviewId,
		List<MultipartFile> images,
		Long memberId) {
		List<StoredImage> uploaded = reviewImageService.uploadReviewImages(reviewId, images, memberId);
		ImageResponse.ImageUploadResult response = ImageResponse.ImageUploadResult.from(
			uploaded.stream().map(ImageResponse.ImageInfo::from).toList());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> deleteReviewImage(
		Long reviewId,
		Long imageId,
		Long memberId) {
		reviewImageService.deleteReviewImage(reviewId, imageId, memberId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@Override
	public ResponseEntity<ApiResponse<ReviewResponse.ReviewInfos>> getReviews(
		Long accommodationId,
		ReviewSortType sortType,
		CursorRequest.ReviewCursorPageRequest cursorRequest) {
		ReviewResponse.ReviewInfos response = ReviewResponse.ReviewInfos.from(
			reviewService.getReviews(accommodationId, cursorRequest, sortType));
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
