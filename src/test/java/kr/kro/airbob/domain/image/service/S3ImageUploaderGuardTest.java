package kr.kro.airbob.domain.image.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import io.awspring.cloud.s3.S3Template;

@ExtendWith(MockitoExtension.class)
class S3ImageUploaderGuardTest {

	@Mock
	private S3Template s3Template;

	@Test
	void disabledUploaderRejectsUploadBeforeTouchingS3() {
		S3ImageUploader uploader = new S3ImageUploader(s3Template, false);
		MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", new byte[] { 1 });

		assertThatThrownBy(() -> uploader.upload(file, "lab"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("S3 writes are disabled");

		then(s3Template).shouldHaveNoInteractions();
	}

	@Test
	void disabledUploaderIgnoresDeleteBeforeTouchingS3() {
		S3ImageUploader uploader = new S3ImageUploader(s3Template, false);

		uploader.delete("https://cdn.example.com/lab/image.png");

		then(s3Template).shouldHaveNoInteractions();
	}
}
