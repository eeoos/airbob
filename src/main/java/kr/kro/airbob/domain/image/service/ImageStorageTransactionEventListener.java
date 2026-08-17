package kr.kro.airbob.domain.image.service;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;
import static org.springframework.transaction.event.TransactionPhase.AFTER_ROLLBACK;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import kr.kro.airbob.domain.image.event.ImageStorageTransactionEvents.ImageDeletionRequested;
import kr.kro.airbob.domain.image.event.ImageStorageTransactionEvents.ImageUploaded;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageStorageTransactionEventListener {

	private final S3ImageUploader s3ImageUploader;

	/**
	 * DB 변경이 확정된 뒤에만 저장소 객체를 삭제한다.
	 * 저장소 장애가 이미 커밋된 API 응답까지 실패로 바꾸지 않도록 예외를 격리한다.
	 */
	@TransactionalEventListener(phase = AFTER_COMMIT)
	public void deleteAfterCommit(ImageDeletionRequested event) {
		deleteBestEffort(event.imageUrl(), "커밋 후 이미지 삭제");
	}

	/**
	 * 트랜잭션 도중 업로드된 객체는 DB 롤백 시 참조되지 않으므로 보상 삭제한다.
	 */
	@TransactionalEventListener(phase = AFTER_ROLLBACK)
	public void cleanupAfterRollback(ImageUploaded event) {
		deleteBestEffort(event.imageUrl(), "롤백 후 업로드 이미지 정리");
	}

	private void deleteBestEffort(String imageUrl, String operation) {
		try {
			s3ImageUploader.delete(imageUrl);
		} catch (RuntimeException exception) {
			log.error("{} 실패: imageUrl={}", operation, imageUrl, exception);
		}
	}
}
