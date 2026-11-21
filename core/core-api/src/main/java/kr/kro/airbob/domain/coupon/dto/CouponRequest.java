package kr.kro.airbob.domain.coupon.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CouponRequest {
	private static final String TIME_INPUT = "오프셋이 있는 ISO 8601. UTC 1000~9999년, 마이크로초 반올림 후 검증·저장";

	@Schema(name = "CouponRequestQuantity")
	public record Quantity(
		@Schema(description = "목표 전체 수량. 기존 수량에 더할 값이 아닙니다.", example = "100", minimum = "1", maximum = "2147483647")
		@NotNull @Positive Integer totalQuantity
	) { }

	@Schema(name = "CouponRequestCreate")
	public record Create(
		@Schema(description = "쿠폰 이름. 빈 값·공백만 있는 값 불가", example = "겨울 여행 할인", minLength = 1, maxLength = Coupon.TEXT_MAX_LENGTH, requiredMode = Schema.RequiredMode.REQUIRED)
		String name,
		@Schema(description = "쿠폰 안내. 생략·null 허용", example = "숙박 예약 할인 쿠폰", maxLength = Coupon.TEXT_MAX_LENGTH, nullable = true)
		String description,
		@Schema(description = "FIXED_AMOUNT는 정액, PERCENTAGE는 정률 할인", example = "FIXED_AMOUNT", requiredMode = Schema.RequiredMode.REQUIRED)
		DiscountType discountType,
		@Schema(description = "정액은 원 단위 양수, 정률은 1~100%", example = "1000", minimum = "1", maximum = "2147483647", requiredMode = Schema.RequiredMode.REQUIRED)
		Integer discountValue,
		@Schema(description = "할인 전 숙박 금액의 하한. 생략·null이면 하한 없음", example = "10000", minimum = "0", nullable = true)
		Integer minPaymentPrice,
		@Schema(description = "정률 할인액 상한. 생략·null이면 상한 없음, 정액에서는 적용하지 않습니다. 0이면 정률 할인액도 0입니다.", example = "5000", minimum = "0", nullable = true)
		Integer maxDiscountAmount,
		@Schema(name = "issue_start_at", description = "발급 시작(포함). 현재보다 미래. " + TIME_INPUT, example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant issueStartAt,
		@Schema(name = "issue_end_at", description = "발급 종료(제외). 발급 시작보다 이후. " + TIME_INPUT, example = "2027-01-31T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant issueEndAt,
		@Schema(description = "사용 시작(포함). " + TIME_INPUT, example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant usableFrom,
		@Schema(description = "사용 종료(제외). 사용 시작보다 이후이며 발급 종료와 같거나 이후. " + TIME_INPUT, example = "2027-02-28T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
		Instant usableUntil,
		@Schema(description = "캠페인 활성 여부. 재고 준비·발급·사용에는 true가 필요합니다.", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
		Boolean isActive,
		@Schema(description = "양의 유한 전체 수량. null·무제한 수량은 생성하지 않습니다.", example = "100", minimum = "1", maximum = "2147483647", requiredMode = Schema.RequiredMode.REQUIRED)
		Integer totalQuantity
	) { }

	@Schema(name = "CouponRequestUpdate", description = "모든 필드는 생략·null이면 기존 값 유지. 병합한 최종 설정을 검증합니다.", example = "{\"description\":\"쿠폰 안내 수정\"}")
	public record Update(
		@Schema(description = "쿠폰 이름. 지정 시 빈 값·공백만 있는 값 불가", example = "겨울 여행 할인", minLength = 1, maxLength = Coupon.TEXT_MAX_LENGTH, nullable = true)
		String name,
		@Schema(description = "쿠폰 안내. null은 유지, 빈 문자열은 안내를 비웁니다.", example = "숙박 예약 할인 쿠폰", maxLength = Coupon.TEXT_MAX_LENGTH, nullable = true)
		String description,
		@Schema(description = "할인 종류. 발급 시작 후에도 수정 가능", example = "FIXED_AMOUNT", nullable = true)
		DiscountType discountType,
		@Schema(description = "정액은 양수, 정률은 1~100. 병합된 할인 종류를 기준으로 검증", example = "1000", minimum = "1", maximum = "2147483647", nullable = true)
		Integer discountValue,
		@Schema(description = "할인 전 숙박 금액의 하한. null은 유지, 0이면 금액 하한 없음", example = "10000", minimum = "0", nullable = true)
		Integer minPaymentPrice,
		@Schema(description = "정률 할인액 상한. null은 유지하므로 기존 상한을 null로 제거할 수 없습니다. 0이면 정률 할인액도 0입니다.", example = "5000", minimum = "0", nullable = true)
		Integer maxDiscountAmount,
		@Schema(name = "issue_start_at", description = "발급 시작. 명시하면 현재보다 미래여야 합니다. " + TIME_INPUT, example = "2027-01-01T00:00:00Z", nullable = true)
		Instant issueStartAt,
		@Schema(name = "issue_end_at", description = "발급 종료. 실제 값 변경은 발급 시작 전에만 허용. " + TIME_INPUT, example = "2027-01-31T00:00:00Z", nullable = true)
		Instant issueEndAt,
		@Schema(description = "사용 시작. 발급 시작 후에도 수정 가능. " + TIME_INPUT, example = "2027-01-01T00:00:00Z", nullable = true)
		Instant usableFrom,
		@Schema(description = "사용 종료. 사용 시작보다 이후이며 발급 종료와 같거나 이후. " + TIME_INPUT, example = "2027-02-28T00:00:00Z", nullable = true)
		Instant usableUntil,
		@Schema(description = "활성 여부. 실제 값 변경은 발급 시작 전에만 허용", example = "true", nullable = true)
		Boolean isActive,
		@Schema(description = "목표 전체 수량. 실제 값 변경은 발급 시작 전에만 허용하며 준비된 재고도 조정", example = "100", minimum = "1", maximum = "2147483647", nullable = true)
		Integer totalQuantity
	) { }
}
