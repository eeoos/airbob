package kr.kro.airbob.core.domain.image;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import kr.kro.airbob.client.s3.S3StorageClient;

@ExtendWith(MockitoExtension.class)
class S3ImageUploaderGuardTest {

	@Mock
	private S3StorageClient s3StorageClient;

	@Test
	void disabledUploaderRejectsUploadBeforeTouchingS3() {
		S3ImageUploader uploader = new S3ImageUploader(s3StorageClient, false);
		MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", new byte[] { 1 });

		assertThatThrownBy(() -> uploader.upload(file, "lab"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("S3 writes are disabled");

		then(s3StorageClient).shouldHaveNoInteractions();
	}

	@Test
	void disabledUploaderIgnoresDeleteBeforeTouchingS3() {
		S3ImageUploader uploader = new S3ImageUploader(s3StorageClient, false);

		uploader.delete("https://cdn.example.com/lab/image.png");

		then(s3StorageClient).shouldHaveNoInteractions();
	}
}
