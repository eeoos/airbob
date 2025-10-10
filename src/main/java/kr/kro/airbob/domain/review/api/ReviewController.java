package kr.kro.airbob.domain.review.api;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
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
	public ResponseEntity<ReviewResponse.CreateResponse> createReview(
		@PathVariable Long accommodationId,
		@Valid @RequestBody ReviewRequest.CreateRequest request) {


		ReviewResponse.CreateResponse response =
			reviewService.createReview(accommodationId, request);

		return ResponseEntity.ok(response);
	}

	@PatchMapping("/v1/accommodations/{accommodationId}/reviews/{reviewId}")
	public ResponseEntity<ReviewResponse.UpdateResponse> updateReview(
		@PathVariable Long reviewId,
		@Valid @RequestBody ReviewRequest.UpdateRequest request) {

		ReviewResponse.UpdateResponse response =
			reviewService.updateReviewContent(reviewId, request);

		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/v1/accommodations/{accommodationId}/reviews/{reviewId}")
	@ResponseStatus(HttpStatus.OK)
	public void deleteReview(@PathVariable Long reviewId) {
		reviewService.deleteReview(reviewId);
	}

	@GetMapping("/v1/accommodations/{accommodationId}/reviews")
	public ResponseEntity<ReviewResponse.ReviewInfos> findReviews(
		@PathVariable Long accommodationId,
		@RequestParam(defaultValue = "LATEST") ReviewSortType sortType,
		@CursorParam CursorRequest.ReviewCursorPageRequest cursorRequest) {

		ReviewResponse.ReviewInfos response =
			reviewService.findReviews(accommodationId, cursorRequest, sortType);

		return ResponseEntity.ok(response);
	}

	// todo: 리뷰 이미지 업로드 해야함
	@GetMapping("/v1/accommodations/{accommodationId}/reviews/summary")
	public ResponseEntity<ReviewResponse.ReviewSummary> findReviewSummary(@PathVariable Long accommodationId) {

		ReviewResponse.ReviewSummary response =
			reviewService.findReviewSummary(accommodationId);

		return ResponseEntity.ok(response);
	}
}
