package kr.kro.airbob.domain.recentlyViewed.dto;

import java.util.List;

import org.hibernate.validator.constraints.UniqueElements;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RecentlyViewedBenchmarkRequest {

	public record Replace(
		@NotEmpty(message = "숙소 ID 목록은 비어 있을 수 없습니다.")
		@Size(max = 100, message = "최근 본 숙소는 최대 100개까지 설정할 수 있습니다.")
		@UniqueElements(message = "숙소 ID는 중복될 수 없습니다.")
		List<
			@NotNull(message = "숙소 ID는 필수입니다.")
			@Positive(message = "숙소 ID는 양수여야 합니다.") Long> accommodationIds
	) {
	}
}
