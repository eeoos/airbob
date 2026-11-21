package kr.kro.airbob.domain.accommodation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import kr.kro.airbob.domain.accommodation.cache.invalidation.AccommodationDetailCacheInvalidationPublisher;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.image.entity.AccommodationImage;
import kr.kro.airbob.domain.image.service.ImageStorageTransactionEventListener;
import kr.kro.airbob.domain.image.service.S3ImageUploader;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;

@SpringJUnitConfig(AccommodationImageServiceTransactionIntegrationTest.TestConfiguration.class)
@DisplayName("숙소 이미지 서비스 트랜잭션 통합 테스트")
class AccommodationImageServiceTransactionIntegrationTest {

	private static final Long ACCOMMODATION_ID = 1L;
	private static final Long IMAGE_ID = 10L;
	private static final Long MEMBER_ID = 2L;
	private static final String IMAGE_URL = "https://cdn.example.com/image.jpg";

	@Autowired private AccommodationImageService accommodationImageService;
	@Autowired private AccommodationImageRepository accommodationImageRepository;
	@Autowired private AccommodationRepository accommodationRepository;
	@Autowired private S3ImageUploader s3ImageUploader;
	@Autowired private AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher;
	@Autowired private AccommodationSearchRefreshPublisher searchRefreshPublisher;
	@Autowired private PlatformTransactionManager transactionManager;

	@BeforeEach
	void resetMocks() {
		reset(
			accommodationImageRepository,
			accommodationRepository,
			s3ImageUploader,
			cacheInvalidationPublisher,
			searchRefreshPublisher
		);
		assertThat(AopUtils.isAopProxy(accommodationImageService)).isTrue();
	}

	@Test
	@DisplayName("이미지 삭제는 서비스 트랜잭션이 커밋된 뒤 S3에 반영한다")
	void deletesStoredImageOnlyAfterServiceTransactionCommits() {
		givenOwnedImage();
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> {
			accommodationImageService.deleteImage(ACCOMMODATION_ID, IMAGE_ID, MEMBER_ID);

			verify(s3ImageUploader, never()).delete(IMAGE_URL);
		});

		verify(s3ImageUploader).delete(IMAGE_URL);
	}

	@Test
	@DisplayName("이미지 삭제 요청 뒤 서비스 트랜잭션이 롤백되면 S3 원본을 유지한다")
	void keepsStoredImageWhenServiceTransactionRollsBack() {
		givenOwnedImage();
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> {
			accommodationImageService.deleteImage(ACCOMMODATION_ID, IMAGE_ID, MEMBER_ID);
			status.setRollbackOnly();

			verify(s3ImageUploader, never()).delete(IMAGE_URL);
		});

		verify(s3ImageUploader, never()).delete(IMAGE_URL);
	}

	@Test
	@DisplayName("S3 업로드 성공 뒤 서비스 트랜잭션이 롤백되면 업로드 객체를 보상 삭제한다")
	void cleansUpUploadedImageWhenServiceTransactionRollsBack() throws IOException {
		Accommodation accommodation = accommodation();
		MockMultipartFile imageFile = new MockMultipartFile(
			"images", "image.jpg", "image/jpeg", new byte[] {1}
		);
		AccommodationImage savedImage = image(accommodation);
		when(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			ACCOMMODATION_ID, MEMBER_ID, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(s3ImageUploader.upload(imageFile, "accommodationInfos/1")).thenReturn(IMAGE_URL);
		when(accommodationImageRepository.saveAll(anyList())).thenReturn(List.of(savedImage));
		when(accommodationImageRepository.findByAccommodationIdOrderByIdAsc(ACCOMMODATION_ID))
			.thenReturn(List.of(savedImage));
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> {
			accommodationImageService.uploadImages(
				ACCOMMODATION_ID, List.of(imageFile), MEMBER_ID);
			status.setRollbackOnly();

			verify(s3ImageUploader, never()).delete(IMAGE_URL);
		});

		verify(s3ImageUploader).delete(IMAGE_URL);
	}

	private void givenOwnedImage() {
		Accommodation accommodation = accommodation();
		AccommodationImage image = image(accommodation);
		when(accommodationRepository.findByIdAndMemberIdAndStatusNot(
			ACCOMMODATION_ID, MEMBER_ID, AccommodationStatus.DELETED))
			.thenReturn(Optional.of(accommodation));
		when(accommodationImageRepository.findById(IMAGE_ID)).thenReturn(Optional.of(image));
	}

	private Accommodation accommodation() {
		return Accommodation.builder()
			.id(ACCOMMODATION_ID)
			.accommodationUid(UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056"))
			.status(AccommodationStatus.DRAFT)
			.build();
	}

	private AccommodationImage image(Accommodation accommodation) {
		return AccommodationImage.builder()
			.id(IMAGE_ID)
			.accommodation(accommodation)
			.imageUrl(IMAGE_URL)
			.build();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	@Import({
		AccommodationImageService.class,
		ImageStorageTransactionEventListener.class
	})
	static class TestConfiguration {

		@Bean
		AccommodationImageRepository accommodationImageRepository() {
			return mock(AccommodationImageRepository.class);
		}

		@Bean
		AccommodationRepository accommodationRepository() {
			return mock(AccommodationRepository.class);
		}

		@Bean
		S3ImageUploader s3ImageUploader() {
			return mock(S3ImageUploader.class);
		}

		@Bean
		AccommodationDetailCacheInvalidationPublisher cacheInvalidationPublisher() {
			return mock(AccommodationDetailCacheInvalidationPublisher.class);
		}

		@Bean
		AccommodationSearchRefreshPublisher searchRefreshPublisher() {
			return mock(AccommodationSearchRefreshPublisher.class);
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new TestTransactionManager();
		}
	}

	private static class TestTransactionManager extends AbstractPlatformTransactionManager {

		private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected boolean isExistingTransaction(Object transaction) {
			return active.get();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
			active.set(true);
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
		}

		@Override
		protected void doCleanupAfterCompletion(Object transaction) {
			active.remove();
		}
	}
}
