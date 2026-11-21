package kr.kro.airbob.domain.image.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import kr.kro.airbob.domain.image.event.ImageStorageTransactionEvents.ImageDeletionRequested;
import kr.kro.airbob.domain.image.event.ImageStorageTransactionEvents.ImageUploaded;

@SpringJUnitConfig(ImageStorageTransactionEventListenerTest.TestConfiguration.class)
@DisplayName("이미지 저장소 트랜잭션 이벤트 테스트")
class ImageStorageTransactionEventListenerTest {

	private static final String IMAGE_URL = "https://cdn.example.com/image.jpg";

	@Autowired private ApplicationEventPublisher applicationEventPublisher;
	@Autowired private S3ImageUploader s3ImageUploader;
	@Autowired private PlatformTransactionManager transactionManager;

	@BeforeEach
	void resetUploader() {
		reset(s3ImageUploader);
	}

	@Test
	@DisplayName("이미지 삭제는 DB 트랜잭션이 커밋된 뒤에 실행한다")
	void deletesImageOnlyAfterCommit() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> {
			applicationEventPublisher.publishEvent(new ImageDeletionRequested(IMAGE_URL));

			verify(s3ImageUploader, never()).delete(IMAGE_URL);
		});

		verify(s3ImageUploader).delete(IMAGE_URL);
	}

	@Test
	@DisplayName("트랜잭션이 롤백되면 업로드된 이미지를 보상 삭제한다")
	void cleansUpUploadedImageAfterRollback() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> {
			applicationEventPublisher.publishEvent(new ImageUploaded(IMAGE_URL));
			status.setRollbackOnly();

			verify(s3ImageUploader, never()).delete(IMAGE_URL);
		});

		verify(s3ImageUploader).delete(IMAGE_URL);
	}

	@Test
	@DisplayName("트랜잭션이 커밋되면 업로드된 이미지를 삭제하지 않는다")
	void keepsUploadedImageAfterCommit() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status ->
			applicationEventPublisher.publishEvent(new ImageUploaded(IMAGE_URL)));

		verify(s3ImageUploader, never()).delete(IMAGE_URL);
	}

	@Test
	@DisplayName("커밋 후 이미지 삭제 실패는 이미 완료된 API를 실패시키지 않는다")
	void isolatesDeleteFailureAfterCommit() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		doThrow(new IllegalStateException("S3 unavailable"))
			.when(s3ImageUploader).delete(IMAGE_URL);

		assertThatCode(() -> transaction.executeWithoutResult(status ->
			applicationEventPublisher.publishEvent(new ImageDeletionRequested(IMAGE_URL))))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("롤백 후 업로드 이미지 정리 실패도 원래 예외 처리를 방해하지 않는다")
	void isolatesCleanupFailureAfterRollback() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		doThrow(new IllegalStateException("S3 unavailable"))
			.when(s3ImageUploader).delete(IMAGE_URL);

		assertThatCode(() -> transaction.executeWithoutResult(status -> {
			applicationEventPublisher.publishEvent(new ImageUploaded(IMAGE_URL));
			status.setRollbackOnly();
		})).doesNotThrowAnyException();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	@Import(ImageStorageTransactionEventListener.class)
	static class TestConfiguration {

		@Bean
		S3ImageUploader s3ImageUploader() {
			return mock(S3ImageUploader.class);
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
