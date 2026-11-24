package kr.kro.airbob.core.domain.image.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.awspring.cloud.s3.S3Template;
import kr.kro.airbob.client.s3.S3StorageClient;

// S3Template comes from spring-cloud-aws auto-configuration (spring-cloud-aws-starter-s3 in core-api).
@Configuration(proxyBeanMethods = false)
public class S3StorageConfiguration {

	@Bean
	public S3StorageClient s3StorageClient(S3Template s3Template, @Value("${cloud.aws.s3.bucket}") String bucket) {
		return new S3StorageClient(s3Template, bucket);
	}
}
