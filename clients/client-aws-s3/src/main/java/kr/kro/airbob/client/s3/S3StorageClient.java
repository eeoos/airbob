package kr.kro.airbob.client.s3;

import java.io.InputStream;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;

/**
 * Object transport for one S3 bucket: upload and delete by key.
 *
 * <p>It does not choose keys or public URLs; callers own key naming and the mapping between keys and
 * CDN URLs. S3 failures surface as {@link io.awspring.cloud.s3.S3Exception} or the AWS SDK's runtime
 * exceptions.
 */
public class S3StorageClient {

	private final S3Template s3Template;
	private final String bucket;

	public S3StorageClient(S3Template s3Template, String bucket) {
		this.s3Template = s3Template;
		this.bucket = bucket;
	}

	public void upload(String key, InputStream content, String contentType, long contentLength) {
		ObjectMetadata metadata = ObjectMetadata.builder()
			.contentType(contentType)
			.contentLength(contentLength)
			.build();

		s3Template.upload(bucket, key, content, metadata);
	}

	public void delete(String key) {
		s3Template.deleteObject(bucket, key);
	}
}
