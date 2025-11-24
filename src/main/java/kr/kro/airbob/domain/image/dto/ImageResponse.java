package kr.kro.airbob.domain.image.dto;

import java.util.List;

import kr.kro.airbob.domain.image.entity.Image;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ImageResponse {

	@Builder
	public record ImageInfo(
		Long id,
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
	public record ImageInfos(
		List<ImageInfo> uploadedImages
	) {
		public static ImageInfos from(List<ImageInfo> imageInfos) {
			return ImageInfos.builder()
				.uploadedImages(imageInfos)
				.build();
		}
	}

}
