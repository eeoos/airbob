package kr.kro.airbob.domain.review.service;

import static kr.kro.airbob.search.event.AccommodationIndexingEvents.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.image.entity.ReviewImage;
import kr.kro.airbob.domain.image.exception.EmptyImageFileException;
import kr.kro.airbob.domain.image.exception.ImageFileSizeExceededException;
import kr.kro.airbob.domain.image.exception.ImageUploadException;
import kr.kro.airbob.domain.image.exception.InvalidImageFormatException;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.dto.ReviewRequest;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.entity.Review;
import kr.kro.airbob.domain.review.entity.ReviewSortType;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.exception.ReviewAccessDeniedException;
import kr.kro.airbob.domain.review.exception.ReviewAlreadyExistsException;
import kr.kro.airbob.domain.review.exception.ReviewCreationForbiddenException;
import kr.kro.airbob.domain.review.exception.ReviewNotFoundException;
import kr.kro.airbob.domain.review.exception.ReviewSummaryNotFoundException;
import kr.kro.airbob.domain.review.exception.ReviewUpdateForbiddenException;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.review.repository.ReviewImageRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewService {

	public static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;
	public static final String IMAGE_JPEG = "image/jpeg";
	public static final String IMAGE_PNG = "image/png";
	private final ReviewRepository reviewRepository;
	private final MemberRepository memberRepository;
	private final ReviewImageRepository reviewImageRepository;
	private final ReservationRepository reservationRepository;
	private final AccommodationRepository accommodationRepository;
	private final AccommodationReviewSummaryRepository summaryRepository;

	private final CursorPageInfoCreator cursorPageInfoCreator;
	private final OutboxEventPublisher outboxEventPublisher;
	private final S3ImageUploader s3ImageUploader;

	@Transactional
	public ReviewResponse.Create createReview(Long accommodationId, ReviewRequest.Create request, Long memberId) {

		Member author = findMemberById(memberId);
		Accommodation accommodation = findAccommodationById(accommodationId);

		validateReviewCreation(accommodationId, memberId);

		Review review = Review.builder()
			.rating(request.rating())
			.content(request.content())
			.accommodation(accommodation)
			.author(author)
			.build();
		Review savedReview = reviewRepository.save(review);

		updateReviewSummaryOnCreate(accommodation, request.rating());
		outboxEventPublisher.save(
			EventType.REVIEW_SUMMARY_CHANGED,
			new ReviewSummaryChangedEvent(accommodation.getAccommodationUid().toString())
		);

		return new ReviewResponse.Create(savedReview.getId());
	}

	@Transactional
	public ReviewResponse.Update updateReviewContent(Long reviewId, ReviewRequest.Update request, Long memberId) {
		Review review = findReviewByIdAndAuthorId(reviewId, memberId);

		if (review.getStatus() != ReviewStatus.PUBLISHED) {
			throw new ReviewUpdateForbiddenException();
		}

		review.updateContent(request.content());
		return new ReviewResponse.Update(review.getId());
	}

	@Transactional
	public void deleteReview(Long reviewId, Long memberId) {
		Review review = findReviewByIdAndAuthorId(reviewId, memberId);

		review.deleteByUser();

		updateReviewSummaryOnDelete(review.getAccommodation().getId(), review.getRating());

		outboxEventPublisher.save(
			EventType.REVIEW_SUMMARY_CHANGED,
			new ReviewSummaryChangedEvent(review.getAccommodation().getAccommodationUid().toString())
		);
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

		Slice<ReviewResponse.ReviewInfo> reviewSlice = reviewRepository.findByAccommodationIdAndStatusWithCursor(
			accommodationId, ReviewStatus.PUBLISHED, lastId, lastCreatedAt, lastRating, sortType, pageRequest);

		List<ReviewResponse.ReviewInfo> reviewInfos = reviewSlice.getContent().stream().toList();

		if (!reviewInfos.isEmpty()) {
			List<Long> reviewIds = reviewInfos.stream()
				.map(ReviewResponse.ReviewInfo::id)
				.toList();

			List<ReviewImage> images = reviewImageRepository.findAllByReview_IdIn(reviewIds);

			Map<Long, List<ReviewResponse.ImageInfo>> imagesByReviewId = images.stream()
				.collect(Collectors.groupingBy(
					reviewImage -> reviewImage.getReview().getId(),
					Collectors.mapping(
						img -> new ReviewResponse.ImageInfo(img.getId(), img.getImageUrl()),
						Collectors.toList()
					)
				));

			reviewInfos = reviewInfos.stream()
				.map(reviewInfo -> {
					List<ReviewResponse.ImageInfo> imageList = imagesByReviewId.get(reviewInfo.id());
					if (imageList == null) {
						return reviewInfo;
					}

					return new ReviewResponse.ReviewInfo(
						reviewInfo.id(),
						reviewInfo.rating(),
						reviewInfo.content(),
						reviewInfo.reviewedAt(),
						reviewInfo.reviewer(),
						imageList
					);
				})
				.toList();
		}

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

	@Transactional
	public ReviewResponse.UploadReviewImages uploadReviewImages(Long reviewId, List<MultipartFile> images,
		Long memberId) {

		Review review = findReviewByIdAndAuthorId(reviewId, memberId);

		List<ReviewResponse.ImageInfo> uploadedImages = new ArrayList<>();
		List<ReviewImage> savedImages = new ArrayList<>();

		for (MultipartFile image : images) {
			validateImageFile(image);

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
			uploadedImages.add(ReviewResponse.ImageInfo.builder()
				.id(savedImage.getId())
				.imageUrl(savedImage.getImageUrl())
				.build());
		}

		return ReviewResponse.UploadReviewImages.builder()
			.uploadedImages(uploadedImages)
			.build();
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

	private void validateReviewAuthor(Long memberId, Review review) {
		if (!review.getAuthor().getId().equals(memberId)) {
			throw new ReviewAccessDeniedException();
		}
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

	private void validateReviewCreation(Long accommodationId, Long memberId) {
		// 예약한 사용자인지와 체크아웃까지 완료했는지 확인
		if (!reservationRepository.existsPastCompletedReservationByGuest(accommodationId, memberId)) {
			throw new ReviewCreationForbiddenException();
		}
		// 이미 리뷰를 작성했는지 확인
		if (reviewRepository.existsByAccommodationIdAndAuthorIdAndStatus(accommodationId, memberId, ReviewStatus.PUBLISHED)) {
			throw new ReviewAlreadyExistsException();
		}
	}

	private Member findMemberById(Long memberId) {
		return memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE).orElseThrow(MemberNotFoundException::new);
	}

	private Accommodation findAccommodationById(Long accommodationId) {
		return accommodationRepository.findByIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED).orElseThrow(AccommodationNotFoundException::new);
	}

	private Review findReviewByIdAndAuthorId(Long reviewId, Long memberId) {
		return reviewRepository.findByIdAndAuthorId(reviewId, memberId).orElseThrow(ReviewNotFoundException::new);
	}

	private AccommodationReviewSummary findSummaryByAccommodationId(Long accommodationId) {
		return summaryRepository.findByAccommodationId(accommodationId).orElseThrow(ReviewSummaryNotFoundException::new);
	}

	private void updateReviewSummaryOnCreate(Accommodation accommodation, int rating) {
		AccommodationReviewSummary summary = summaryRepository.findByAccommodationId(accommodation.getId())
			.orElseGet(() -> createNewSummary(accommodation));

		summary.addReview(rating);
		summaryRepository.save(summary); // 명시적 save
	}

	private void updateReviewSummaryOnDelete(Long accommodationId, int rating) {
		AccommodationReviewSummary summary = findSummaryByAccommodationId(accommodationId);

		summary.removeReview(rating);

		if (summary.getTotalReviewCount() == 0) {
			summaryRepository.delete(summary);
		}
	}

	private AccommodationReviewSummary createNewSummary(Accommodation accommodation) {
		return AccommodationReviewSummary.builder()
			.accommodation(accommodation)
			.build();
	}
}
