package kr.kro.airbob.domain.review;

import static kr.kro.airbob.search.event.AccommodationIndexingEvents.*;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.Member;
import kr.kro.airbob.domain.member.MemberRepository;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.review.dto.ReviewRequest;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.exception.ReviewNotFoundException;
import kr.kro.airbob.domain.review.exception.ReviewSummaryNotFoundException;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.search.event.AccommodationIndexingEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewService {

	private final ReviewRepository reviewRepository;
	private final MemberRepository memberRepository;
	private final AccommodationRepository accommodationRepository;
	private final AccommodationReviewSummaryRepository summaryRepository;

	private final CursorPageInfoCreator cursorPageInfoCreator;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public ReviewResponse.CreateResponse createReview(Long accommodationId, ReviewRequest.CreateRequest request, Long memberId) {

		Member author = findMemberById(memberId);
		Accommodation accommodation = findAccommodationById(accommodationId);

		Review review = Review.builder()
			.rating(request.rating())
			.content(request.content())
			.accommodation(accommodation)
			.author(author)
			.build();

		Review savedReview = reviewRepository.save(review);

		updateReviewSummaryOnCreate(accommodationId, request.rating());

		eventPublisher.publishEvent(new ReviewSummaryChangedEvent(accommodation.getAccommodationUid().toString()));

		return new ReviewResponse.CreateResponse(savedReview.getId());
	}

	@Transactional
	public ReviewResponse.UpdateResponse updateReviewContent(Long reviewId, ReviewRequest.UpdateRequest request) {
		Review review = findReviewById(reviewId);

		review.updateContent(request.content());

		return new ReviewResponse.UpdateResponse(review.getId());
	}

	@Transactional
	public void deleteReview(Long reviewId) {
		Review review = findReviewById(reviewId);
		Accommodation accommodation = review.getAccommodation();
		int rating = review.getRating();

		reviewRepository.delete(review);

		updateReviewSummaryOnDelete(accommodation.getId(), rating);

		eventPublisher.publishEvent(new ReviewSummaryChangedEvent(accommodation.getAccommodationUid().toString()));
	}

	@Transactional(readOnly = true)
	public ReviewResponse.ReviewInfos findReviews(
		Long accommodationId,
		CursorRequest.ReviewCursorPageRequest cursorRequest,
		ReviewSortType sortType) {

		PageRequest pageRequest = PageRequest.of(0, cursorRequest.size());
		Long lastId = cursorRequest.lastId();
		LocalDateTime lastCreatedAt = cursorRequest.lastCreatedAt();
		Integer lastRating = cursorRequest.lastRating();

		Slice<ReviewResponse.ReviewInfo> reviewSlice = reviewRepository.findByAccommodationIdWithCursor(
			accommodationId, lastId, lastCreatedAt, lastRating, sortType, pageRequest);

		List<ReviewResponse.ReviewInfo> reviewInfos = reviewSlice.getContent().stream().toList();

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			reviewSlice.getContent(),
			reviewSlice.hasNext(),
			ReviewResponse.ReviewInfo::id,
			ReviewResponse.ReviewInfo::reviewedAt,
			ReviewResponse.ReviewInfo::rating
		);

		return new ReviewResponse.ReviewInfos(reviewInfos, pageInfo);
	}

	@Transactional(readOnly = true)
	public ReviewResponse.ReviewSummary findReviewSummary(Long accommodationId) {

		AccommodationReviewSummary summary = summaryRepository.findByAccommodationId(accommodationId)
			.orElse(null);

		return ReviewResponse.ReviewSummary.of(summary);
	}

	private Member findMemberById(Long memberId) {
		return memberRepository.findById(memberId).orElseThrow(MemberNotFoundException::new);
	}

	private Accommodation findAccommodationById(Long accommodationId) {
		return accommodationRepository.findById(accommodationId).orElseThrow(AccommodationNotFoundException::new);
	}

	private Review findReviewById(Long reviewId) {
		return reviewRepository.findById(reviewId).orElseThrow(ReviewNotFoundException::new);
	}

	private AccommodationReviewSummary findSummaryByAccommodationId(Long accommodationId) {
		return summaryRepository.findByAccommodationId(accommodationId).orElseThrow(ReviewSummaryNotFoundException::new);
	}

	private void updateReviewSummaryOnCreate(Long accommodationId, int rating) {
		AccommodationReviewSummary summary = summaryRepository.findByAccommodationId(accommodationId)
			.orElseGet(() -> createNewSummary(accommodationId));

		summary.addReview(rating);
		summaryRepository.save(summary); // 명시적 save
	}

	private void updateReviewSummaryOnUpdate(Long accommodationId, int oldRating, int newRating) {

		AccommodationReviewSummary summary = findSummaryByAccommodationId(accommodationId);

		summary.updateReview(oldRating, newRating);
	}

	private void updateReviewSummaryOnDelete(Long accommodationId, int rating) {
		AccommodationReviewSummary summary = findSummaryByAccommodationId(accommodationId);

		summary.removeReview(rating);

		if (summary.getTotalReviewCount() == 0) {
			summaryRepository.delete(summary);
		}
	}

	private AccommodationReviewSummary createNewSummary(Long accommodationId) {
		Accommodation accommodation = findAccommodationById(accommodationId);
		return AccommodationReviewSummary.builder()
			.accommodation(accommodation)
			.build();
	}
}
