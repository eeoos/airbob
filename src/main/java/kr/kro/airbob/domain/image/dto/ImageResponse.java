package kr.kro.airbob.domain.image.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.domain.image.entity.Image;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ImageResponse {

	@Builder
	@Schema(name = "ImageResponseImageInfo")
	public record ImageInfo(
		@Schema(description = "이미지 ID. 이미지 삭제 경로의 imageId로 사용합니다.", example = "301")
		Long id,
		@Schema(description = "이미지 조회 주소", example = "https://example.invalid/accommodation-301.png")
		String imageUrl
	){
		public static ImageInfo from(Image image) {
			return ImageInfo.builder()
				.id(image.getId())
				.imageUrl(image.getImageUrl())
				.build();
		}
	}

	@Builder
	@Schema(name = "ImageResponseImageUploadResult")
	public record ImageUploadResult(
		@Schema(description = "이번 요청으로 추가된 이미지 목록")
		List<ImageInfo> uploadedImages
	) {
		public static ImageUploadResult from(List<ImageInfo> imageInfos) {
			return ImageUploadResult.builder()
				.uploadedImages(imageInfos)
				.build();
		}
	}

}
