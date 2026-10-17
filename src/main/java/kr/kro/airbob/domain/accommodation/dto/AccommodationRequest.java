package kr.kro.airbob.domain.accommodation.dto;

import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.kro.airbob.common.validation.NotBlankWhenPresent;
import kr.kro.airbob.domain.accommodation.dto.AmenityRequest.AmenityInfo;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationRequest {

    @Builder
    @Schema(name = "AccommodationRequestUpdate", description = "부분 수정 요청. 생략하거나 null인 필드는 유지합니다. 주소·인원 정책을 보내면 해당 객체의 필수 항목을 모두 채웁니다.")
    public record Update(
        @Size(min = 1, max = 50, message = "이름은 1~50자여야 합니다.")
        @NotBlankWhenPresent(message = "이름은 공백만으로 구성할 수 없습니다.")
        @Schema(description = "숙소 이름. 공백만 있는 값은 허용하지 않습니다.", example = "도심 속 작은 숙소", nullable = true,
            pattern = "[\\s\\S]*\\S[\\s\\S]*")
        String name,

        @Size(min = 1, max = 5000, message = "설명은 1~5000자여야 합니다.")
        @NotBlankWhenPresent(message = "설명은 공백만으로 구성할 수 없습니다.")
        @Schema(description = "숙소 설명. 공백만 있는 값은 허용하지 않습니다.", example = "대중교통으로 이동하기 편리한 숙소입니다.", nullable = true,
            pattern = "[\\s\\S]*\\S[\\s\\S]*")
        String description,

        @Positive(message = "기본 가격은 1 이상이어야 합니다.")
        @Schema(description = "1박 기본 요금. 원 단위 양의 정수입니다.", example = "100000", minimum = "1", nullable = true)
        Long basePrice,

        @Pattern(regexp = "KRW", message = "현재 지원하는 통화는 KRW입니다.")
        @Schema(description = "현재는 KRW만 지원합니다.", example = "KRW", nullable = true)
        String currency,

        @Valid
        @Schema(description = "주소 전체 교체. 좌표와 시간대는 서버가 주소로 계산합니다.", nullable = true)
        AddressRequest.AddressInfo addressInfo,

        @Schema(description = "편의시설 전체 교체. 빈 배열은 모두 삭제하고 null은 유지합니다. 코드 대소문자를 통일해 중복 개수를 합산하며 0개 항목은 제외합니다.", nullable = true)
        List<@NotNull @Valid AmenityInfo> amenityInfos,

        @Valid
        @Schema(description = "수용 인원 정책 전체 교체. 세 항목을 모두 전달합니다.", nullable = true)
        PolicyRequest.OccupancyPolicyInfo occupancyPolicyInfo,

        @Size(max = 50)
        @Schema(description = "ACCOMMODATION_TYPE 공통 코드의 활성 code. 대문자로 저장합니다.", example = "APARTMENT", nullable = true)
        String type,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm[:ss]")
        @Schema(description = "숙소 현지 체크인 시간. HH:mm 또는 HH:mm:ss. 체크아웃 시간보다 이르면 안 됩니다.", example = "15:00", nullable = true)
        LocalTime checkInTime,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm[:ss]")
        @Schema(description = "숙소 현지 체크아웃 시간. HH:mm 또는 HH:mm:ss. 체크인 시간보다 늦으면 안 됩니다.", example = "11:00", nullable = true)
        LocalTime checkOutTime
    ){
    }
}
