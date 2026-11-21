package kr.kro.airbob.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.image.entity.ReviewImage;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.review.entity.Review;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.repository.ReviewImageRepository;
import kr.kro.airbob.domain.review.repository.ReviewRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("리뷰 이미지 서비스 단위 테스트")
class ReviewImageServiceTest {

	@Mock private ReviewRepository reviewRepository;
	@Mock private ReviewImageRepository reviewImageRepository;
	@Mock private S3ImageUploader s3ImageUploader;

	@InjectMocks
	private ReviewImageService reviewImageService;

	@Test
	@DisplayName("리뷰 이미지 업로드 결과에 저장된 이미지 ID와 URL을 반환한다")
	void returnsUploadedImageIdsAndUrls() throws IOException {
		Review review = review(10L, 4, accommodation(), member());
		MockMultipartFile image = new MockMultipartFile(
			"images", "review.jpg", "image/jpeg", new byte[] {1});
		ReviewImage savedImage = ReviewImage.builder()
			.id(20L)
			.review(review)
			.imageUrl("https://cdn.example.com/review.jpg")
			.build();
		when(reviewRepository.findByIdAndAuthorId(10L, 2L)).thenReturn(Optional.of(review));
		when(s3ImageUploader.upload(image, "reviews/10"))
			.thenReturn(savedImage.getImageUrl());
		when(reviewImageRepository.saveAll(anyList())).thenReturn(List.of(savedImage));

		ImageResponse.ImageUploadResult response = reviewImageService.uploadReviewImages(10L, List.of(image), 2L);

		assertThat(response.uploadedImages()).containsExactly(
			new ImageResponse.ImageInfo(20L, "https://cdn.example.com/review.jpg"));
	}

	@Test
	@DisplayName("본인 리뷰의 이미지 파일과 연결을 삭제한다")
	void deletesOwnedImage() {
		Review review = review(10L, 4, accommodation(), member());
		ReviewImage image = ReviewImage.builder()
			.id(20L)
			.review(review)
			.imageUrl("https://cdn.example.com/review.jpg")
			.build();
		when(reviewImageRepository.findByIdAndReviewAuthorId(20L, 2L))
			.thenReturn(Optional.of(image));

		reviewImageService.deleteReviewImage(10L, 20L, 2L);

		verify(s3ImageUploader).delete(image.getImageUrl());
		verify(reviewImageRepository).delete(image);
	}

	@Test
	@DisplayName("뒤쪽 파일이 유효하지 않으면 앞쪽 파일도 저장소에 업로드하지 않는다")
	void validatesEveryFileBeforeUploadingAnyFile() throws IOException {
		Review review = review(10L, 4, accommodation(), member());
		when(reviewRepository.findByIdAndAuthorId(10L, 2L)).thenReturn(Optional.of(review));
		var valid = new MockMultipartFile("images", "review.png", "image/png", new byte[] {1});
		var invalid = new MockMultipartFile("images", "invalid.txt", "text/plain", new byte[] {1});
		assertThatThrownBy(() -> reviewImageService.uploadReviewImages(10L, List.of(valid, invalid), 2L))
			.isInstanceOf(kr.kro.airbob.domain.image.exception.InvalidImageFormatException.class);
		verifyNoInteractions(s3ImageUploader, reviewImageRepository);
	}

	private Accommodation accommodation() {
		return Accommodation.builder()
			.id(1L)
			.accommodationUid(UUID.fromString("11111111-1111-1111-1111-111111111111"))
			.status(AccommodationStatus.PUBLISHED)
			.build();
	}

	private Member member() {
		return Member.builder()
			.id(2L)
			.status(MemberStatus.ACTIVE)
			.build();
	}

	private Review review(Long id, int rating, Accommodation accommodation, Member author) {
		return Review.builder()
			.id(id)
			.rating(rating)
			.content("기존 내용")
			.status(ReviewStatus.PUBLISHED)
			.accommodation(accommodation)
			.author(author)
			.build();
	}
}
