package kr.kro.airbob.domain.accommodation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationRequest {

    @Builder
    public record Update(
        @Size(min = 1, max = 50, message = "이름은 1 ~ 50자 이여야 합니다!")
        String name,

        @Size(min = 1, max = 5000, message = "설명은 1 ~ 5000자 이여야 합니다!")
        String description,

        @Positive(message = "기본 가격은 1 이상이어야 합니다.")
        Long basePrice,

        String currency,

        @Valid
        AddressInfo addressInfo,

        List<@Valid AmenityInfo> amenityInfos,

        @Valid
        OccupancyPolicyInfo occupancyPolicyInfo,

        String type,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm[:ss]")
        LocalTime checkInTime,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm[:ss]")
        LocalTime checkOutTime
    ){
    }

    @Builder
    public record AddressInfo(
        @NotBlank(message = "우편번호는 필수입니다.")
        @Size(max = 12, message = "우편번호는 최대 12자입니다.")
        String postalCode,

        @NotBlank(message = "국가는 필수입니다.")
        String country,

        @NotBlank(message = "행정구역(시/도/주)는 필수입니다.")
        String state,
        @NotBlank(message = "도시는 필수입니다.")
        String city,

        @NotBlank(message = "상세 주소는 필수입니다.")
        String detail,
        @NotBlank(message = "지역구는 필수입니다.")
        String district,
        @NotBlank(message = "도로명 주소는 필수입니다.")
        String street
    ){
    }

    @Builder
    public record AmenityInfo (
        @NotBlank(message = "편의시설 이름은 필수입니다.")
        String name,

        @NotNull(message = "편의시설 개수는 필수입니다.")
        @PositiveOrZero(message = "편의시설 개수는 0 이상이어야 합니다.")
        Integer count
    ) {
    }

    @Builder
    public record OccupancyPolicyInfo(
        @NotNull(message = "최대 수용 인원은 필수입니다.")
        @Positive(message = "최대 수용 인원은 1 이상이어야 합니다.")
        Integer maxOccupancy,

        @NotNull(message = "유아 수용 인원은 필수입니다.")
        @PositiveOrZero(message = "유아 수용 인원은 0 이상이어야 합니다.")
        Integer infantOccupancy,

        @NotNull(message = "반려동물 수용 인원은 필수입니다.")
        @PositiveOrZero(message = "반려동물 수용 인원은 0 이상이어야 합니다.")
        Integer petOccupancy
        ){
    }
}
