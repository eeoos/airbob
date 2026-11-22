package kr.kro.airbob.domain.reservation.port;

/**
 * 게스트 예약 상세의 리뷰 작성 가능 여부에 필요한 리뷰 정보. 리뷰 도메인이 구현하므로
 * reservation은 review에 의존하지 않는다(review는 작성 자격 확인을 위해 이미 reservation에 의존한다).
 */
public interface ReservationReviewReader {

	/** 회원이 이 숙소에 게시한 리뷰가 있는지. */
	boolean hasPublishedReview(Long accommodationId, Long memberId);
}
