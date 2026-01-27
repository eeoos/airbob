package kr.kro.airbob.domain.image.service;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class S3ImageUploader {

	private final S3Template s3Template;

	@Value("${cloud.aws.s3.bucket}")
	private String bucket;

	@Value("${cloud.cloudfront.domain}")
	private String cloudfrontDomain;

	public String upload(MultipartFile multipartFile, String dirName) throws IOException {

		String originalFilename = multipartFile.getOriginalFilename();
		String ext = originalFilename.substring(originalFilename.lastIndexOf(".") + 1);
		String s3FileName = dirName + "/" + UUID.randomUUID() + "." + ext;

		ObjectMetadata metadata = ObjectMetadata.builder()
			.contentType(multipartFile.getContentType())
			.contentLength(multipartFile.getSize())
			.build();

		try {
			s3Template.upload(bucket, s3FileName, multipartFile.getInputStream(), metadata);
		} catch (IOException e) {
			log.error("S3 파일 업로드 실패: {}", s3FileName, e);
			throw new IOException("파일 업로드 중 오류가 발생했습니다.", e);
		}
		return cloudfrontDomain + "/" + s3FileName;
	}

	public void delete(String fileUrl) {
		try {
			String s3FileKey = extractS3KeyFromUrl(fileUrl);
			if (!s3FileKey.isEmpty()) {
				s3Template.deleteObject(bucket, s3FileKey);
				log.info("s3 파일 삭제 성공: {}", s3FileKey);
			} else {
				log.warn("s3 파일 삭제 시도 - 유효하지 않은 키: {}", fileUrl);
			}
		} catch (Exception e) {
			log.error("s3 파일 삭제 실패: {}", fileUrl, e);
		}
	}

	private String extractS3KeyFromUrl(String fileUrl) {
		if (fileUrl == null || !fileUrl.startsWith(cloudfrontDomain)) {
			log.warn("유효하지 않은 이미지 URL 감지: {}", fileUrl);
			// s3 url 형식의 경우 로직
			if (fileUrl != null && fileUrl.contains(bucket) && fileUrl.contains("s3")) {
				try {
					URL url = new URL(fileUrl);
					return url.getPath().substring(1);
				} catch (MalformedURLException e) {
					log.error("잘못된 s3 URL 형식: {}", fileUrl, e);
					return "";
				}
			}
			return "";
		}
		return fileUrl.substring(cloudfrontDomain.length() + 1);
	}
}
