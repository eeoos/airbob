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
        AddressRequest.AddressInfo addressInfo,

        List<AmenityRequest.@Valid AmenityInfo> amenityInfos,

        @Valid
        PolicyRequest.OccupancyPolicyInfo occupancyPolicyInfo,

        String type,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm[:ss]")
        LocalTime checkInTime,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm[:ss]")
        LocalTime checkOutTime
    ){
    }
}
