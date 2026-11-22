package kr.kro.airbob.core.domain.image;

/** 저장소에 올라간 숙소·리뷰 이미지의 ID와 조회 주소. */
public record StoredImage(
	Long id,
	String imageUrl
) {

	public static StoredImage from(Image image) {
		return new StoredImage(image.getId(), image.getImageUrl());
	}
}
