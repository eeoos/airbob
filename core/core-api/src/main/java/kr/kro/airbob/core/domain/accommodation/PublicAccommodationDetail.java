package kr.kro.airbob.core.domain.accommodation;

/** 공개 숙소 상세. 캐시에서 읽은 공용 스냅샷과 요청자의 찜 여부(비로그인이면 false). */
public record PublicAccommodationDetail(
	AccommodationDetailSnapshot detail,
	boolean inWishlist) {
}
