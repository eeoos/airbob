package kr.kro.airbob.client.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;

@DisplayName("S3 storage client")
class S3StorageClientTest {

	private final S3Template s3Template = mock(S3Template.class);
	private final S3StorageClient client = new S3StorageClient(s3Template, "airbob-images");

	@Test
	void uploadSendsTheStreamToTheBucketWithContentTypeAndLength() {
		InputStream content = new ByteArrayInputStream(new byte[] {1, 2, 3});

		client.upload("reviews/7/abc.png", content, "image/png", 3L);

		ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
		then(s3Template).should().upload(eq("airbob-images"), eq("reviews/7/abc.png"), eq(content), metadata.capture());
		assertThat(metadata.getValue().getContentType()).isEqualTo("image/png");
		assertThat(metadata.getValue().getContentLength()).isEqualTo(3L);
	}

	@Test
	void deleteRemovesTheKeyFromTheBucket() {
		client.delete("reviews/7/abc.png");

		then(s3Template).should().deleteObject("airbob-images", "reviews/7/abc.png");
	}
}
