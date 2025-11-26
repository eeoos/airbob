package kr.kro.airbob.domain.review.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.review.dto.ReviewRequest;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.entity.ReviewSortType;
import kr.kro.airbob.domain.review.service.ReviewService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ReviewController {

	private final ReviewService reviewService;

	@PostMapping("/v1/accommodations/{accommodationId}/reviews")
	public ResponseEntity<ApiResponse<ReviewResponse.Create>> createReview(
		@PathVariable Long accommodationId,
		@Valid @RequestBody ReviewRequest.Create request) {
		Long memberId = UserContext.get().id();
		ReviewResponse.Create response =
			reviewService.createReview(accommodationId, request, memberId);

		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@PatchMapping("/v1/reviews/{reviewId}")
	public ResponseEntity<ApiResponse<ReviewResponse.Update>> updateReview(
		@PathVariable Long reviewId,
		@Valid @RequestBody ReviewRequest.Update request) {
		Long memberId = UserContext.get().id();
		ReviewResponse.Update response =
			reviewService.updateReviewContent(reviewId, request, memberId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@DeleteMapping("/v1/reviews/{reviewId}")
	public ResponseEntity<ApiResponse<Void>> deleteReview(@PathVariable Long reviewId) {
		Long memberId = UserContext.get().id();
		reviewService.deleteReview(reviewId, memberId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@PostMapping("/v1/reviews/{reviewId}/images")
	public ResponseEntity<ApiResponse<ImageResponse.ImageUploadResult>> uploadReviewImages(
		@PathVariable Long reviewId,
		@RequestParam("images") List<MultipartFile> images) {

		Long memberId = UserContext.get().id();
		ImageResponse.ImageUploadResult response = reviewService.uploadReviewImages(reviewId, images,
			memberId);

		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@DeleteMapping("/v1/reviews/{reviewId}/images/{imageId}")
	public ResponseEntity<ApiResponse<Void>> deleteReviewImage(
		@PathVariable Long reviewId,
		@PathVariable Long imageId) {

		Long memberId = UserContext.get().id();
		reviewService.deleteReviewImage(reviewId, imageId, memberId);

		return ResponseEntity.ok(ApiResponse.success());
	}

	@GetMapping("/v1/accommodations/{accommodationId}/reviews")
	public ResponseEntity<ApiResponse<ReviewResponse.ReviewInfos>> findReviews(
		@PathVariable Long accommodationId,
		@RequestParam(defaultValue = "LATEST") ReviewSortType sortType,
		@CursorParam CursorRequest.ReviewCursorPageRequest cursorRequest) {

		ReviewResponse.ReviewInfos response =
			reviewService.findReviews(accommodationId, cursorRequest, sortType);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/v1/accommodations/{accommodationId}/reviews/summary")
	public ResponseEntity<ApiResponse<ReviewResponse.ReviewSummary>> findReviewSummary(@PathVariable Long accommodationId) {

		ReviewResponse.ReviewSummary response =
			reviewService.findReviewSummary(accommodationId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
