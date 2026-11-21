package kr.kro.airbob.domain.coupon.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.common.CouponTime;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.common.MemberCouponStatus;
import kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignProjection;
import kr.kro.airbob.domain.coupon.repository.projection.MemberCouponProjection;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CouponResponse {

	@Schema(name = "CouponResponseCreate")
	public record Create(
		@Schema(description = "생성된 캠페인 ID. 재고 준비·수정·삭제 API의 couponId에 사용합니다.", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
		Long id
	) { }

	@Schema(name = "CouponResponseCouponInfo")
	public record CouponInfo(
		@Schema(description = "캠페인 ID. 발급 API의 couponId", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,
		@Schema(description = "쿠폰 이름", example = "겨울 여행 할인", requiredMode = Schema.RequiredMode.REQUIRED)
		String name,
		@Schema(description = "쿠폰 안내. 없으면 null", example = "숙박 예약 할인 쿠폰", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String description,
		@Schema(description = "정액 또는 정률 할인", example = "FIXED_AMOUNT", requiredMode = Schema.RequiredMode.REQUIRED)
		DiscountType discountType,
		@Schema(description = "정액은 원 단위, 정률은 퍼센트", example = "1000", requiredMode = Schema.RequiredMode.REQUIRED)
		Integer discountValue,
		@Schema(description = "할인 전 숙박 금액의 하한. null이면 하한 없음", example = "10000", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Integer minPaymentPrice,
		@Schema(description = "정률 할인액 상한. null이면 상한 없음, 정액에서는 적용하지 않음", example = "5000", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Integer maxDiscountAmount,
		@Schema(name = "issue_start_at", description = "발급 시작 UTC 시각(포함)", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant issueStartAt,
		@Schema(name = "issue_end_at", description = "발급 종료 UTC 시각(제외)", example = "2027-01-31T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant issueEndAt,
		@Schema(description = "사용 시작 UTC 시각(포함)", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant usableFrom,
		@Schema(description = "사용 종료 UTC 시각(제외)", example = "2027-02-28T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant usableUntil,
		@Schema(description = "캠페인 전체 수량으로 잔여 수량이 아닙니다. 과거 데이터는 null일 수 있습니다.", example = "100", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Integer totalQuantity,
		@Schema(name = "issuance_status", description = "시작 전 UPCOMING 우선. 시작 후 total_quantity가 null이 아니고 잔여 재고가 없으면 SOLD_OUT, 그 외 OPEN. 실제 발급은 다시 검증합니다.", example = "UPCOMING", requiredMode = Schema.RequiredMode.REQUIRED)
		CouponIssuanceStatus issuanceStatus
	) {
		public static CouponInfo of(CouponCampaignProjection coupon, LocalDateTime now) {
			return new CouponInfo(
				coupon.id(),
				coupon.name(),
				coupon.description(),
				coupon.discountType(),
				coupon.discountValue(),
				coupon.minPaymentPrice(),
				coupon.maxDiscountAmount(),
				CouponTime.toInstant(coupon.issueStartAt()),
				CouponTime.toInstant(coupon.issueEndAt()),
				CouponTime.toInstant(coupon.usableFrom()),
				CouponTime.toInstant(coupon.usableUntil()),
				coupon.totalQuantity(),
				CouponIssuanceStatus.resolve(coupon.issueStartAt(), coupon.totalQuantity(), coupon.hasRemainingStock(), now));
		}
	}

	@Schema(name = "CouponResponseCouponInfos")
	public record CouponInfos(
		@Schema(description = "현재 페이지 캠페인 목록. 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
		List<CouponInfo> infos,
		@Schema(description = "다음 페이지 여부·커서·현재 항목 수", requiredMode = Schema.RequiredMode.REQUIRED)
		CursorResponse.PageInfo pageInfo
	) {

	}

	@Schema(name = "CouponResponseMemberCouponInfo")
	public record MemberCouponInfo(
		@Schema(description = "캠페인 ID. 예약 견적의 coupon_id에 사용하며 보유 내역 ID와 다릅니다.", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
		Long couponId,
		@Schema(description = "현재 캠페인의 쿠폰 이름", example = "겨울 여행 할인", requiredMode = Schema.RequiredMode.REQUIRED)
		String name,
		@Schema(description = "현재 쿠폰 안내. 없으면 null", example = "숙박 예약 할인 쿠폰", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String description,
		@Schema(description = "현재 할인 종류", example = "FIXED_AMOUNT", requiredMode = Schema.RequiredMode.REQUIRED)
		DiscountType discountType,
		@Schema(description = "정액은 원 단위, 정률은 퍼센트", example = "1000", requiredMode = Schema.RequiredMode.REQUIRED)
		Integer discountValue,
		@Schema(description = "할인 전 숙박 금액의 하한. null이면 하한 없음", example = "10000", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Integer minPaymentPrice,
		@Schema(description = "정률 할인액 상한. null이면 상한 없음, 정액에서는 적용하지 않음", example = "5000", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		Integer maxDiscountAmount,
		@Schema(description = "사용 시작 UTC 시각(포함)", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant usableFrom,
		@Schema(description = "사용 종료 UTC 시각(제외)", example = "2027-02-28T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant usableUntil,
		@Schema(description = "USED 우선, 그다음 사용 종료 도달 시 EXPIRED, 비활성 UNAVAILABLE, 사용 시작 전 UPCOMING, 그 외 AVAILABLE. 금액 적용 조건은 견적에서 다시 확인합니다.", example = "AVAILABLE", requiredMode = Schema.RequiredMode.REQUIRED)
		MemberCouponStatus status
	) {
		public static MemberCouponInfo of(MemberCouponProjection coupon, LocalDateTime now) {
			return new MemberCouponInfo(
				coupon.couponId(),
				coupon.name(),
				coupon.description(),
				coupon.discountType(),
				coupon.discountValue(),
				coupon.minPaymentPrice(),
				coupon.maxDiscountAmount(),
				CouponTime.toInstant(coupon.usableFrom()),
				CouponTime.toInstant(coupon.usableUntil()),
				MemberCouponStatus.resolve(coupon.used(), coupon.active(), coupon.usableFrom(),
					coupon.usableUntil(), now));
		}
	}

	@Schema(name = "CouponResponseMemberCouponInfos")
	public record MemberCouponInfos(
		@Schema(description = "현재 페이지 보유 쿠폰 목록. 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
		List<MemberCouponInfo> infos,
		@Schema(description = "다음 페이지 여부·커서·현재 항목 수", requiredMode = Schema.RequiredMode.REQUIRED)
		CursorResponse.PageInfo pageInfo
	) {

	}
}
