package kr.kro.airbob.domain.accommodation.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.service.AccommodationQueryService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AccommodationController implements AccommodationApi {

    private final AccommodationQueryService accommodationQueryService;

    @Override
    public ResponseEntity<ApiResponse<AccommodationResponse.DetailInfo>> getAccommodation(
        Long accommodationId,
        Long viewerId) {
        AccommodationResponse.DetailInfo response = accommodationQueryService.findAccommodation(accommodationId,
            viewerId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Override
    public ResponseEntity<ApiResponse<AccommodationResponse.Availability>> getAccommodationAvailability(
        Long accommodationId) {
        AccommodationResponse.Availability response =
            accommodationQueryService.findAccommodationAvailability(accommodationId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
