package kr.kro.airbob.domain.review.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.review.dto.ReviewRequest;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.entity.ReviewSortType;
import kr.kro.airbob.domain.review.service.ReviewImageService;
import kr.kro.airbob.domain.review.service.ReviewService;
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
		ReviewResponse.Create response = reviewService.createReview(accommodationId, request, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<ReviewResponse.Update>> updateReview(
		Long reviewId,
		ReviewRequest.Update request,
		Long memberId) {
		ReviewResponse.Update response = reviewService.updateReviewContent(reviewId, request, memberId);
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
		ImageResponse.ImageUploadResult response = reviewImageService.uploadReviewImages(reviewId, images, memberId);
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
	public ResponseEntity<ApiResponse<ReviewResponse.ReviewInfos>> findReviews(
		Long accommodationId,
		ReviewSortType sortType,
		CursorRequest.ReviewCursorPageRequest cursorRequest) {
		ReviewResponse.ReviewInfos response = reviewService.findReviews(accommodationId, cursorRequest, sortType);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
