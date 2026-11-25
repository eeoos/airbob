package kr.kro.airbob.core.domain.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import kr.kro.airbob.client.s3.S3StorageClient;

class S3ImageUploaderTest {

	private static final String CLOUDFRONT = "https://cdn.example.com";
	private static final String BUCKET = "airbob-images";

	private final S3StorageClient s3StorageClient = mock(S3StorageClient.class);
	private final S3ImageUploader uploader = uploader();

	@Test
	void uploadStoresUnderARandomKeyAndReturnsTheCloudFrontUrl() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "room.photo.png", "image/png", new byte[] {1, 2, 3});

		String url = uploader.upload(file, "accommodationInfos/1");

		ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
		then(s3StorageClient).should().upload(key.capture(), any(InputStream.class), eq("image/png"), eq(3L));
		assertThat(key.getValue()).matches("accommodationInfos/1/[0-9a-f-]{36}\\.png");
		assertThat(url).isEqualTo(CLOUDFRONT + "/" + key.getValue());
	}

	@Test
	void deleteTurnsACloudFrontUrlIntoItsKey() {
		uploader.delete(CLOUDFRONT + "/reviews/7/abc.png");

		then(s3StorageClient).should().delete("reviews/7/abc.png");
	}

	@Test
	void deleteTurnsALegacyS3UrlIntoItsPath() {
		uploader.delete("https://" + BUCKET + ".s3.ap-northeast-2.amazonaws.com/reviews/7/abc.png");

		then(s3StorageClient).should().delete("reviews/7/abc.png");
	}

	@Test
	void deleteIgnoresUrlsOutsideTheBucket() {
		uploader.delete("https://elsewhere.example.com/reviews/7/abc.png");

		then(s3StorageClient).should(never()).delete(anyString());
	}

	private S3ImageUploader uploader() {
		return new S3ImageUploader(s3StorageClient, true, BUCKET, CLOUDFRONT);
	}
}
