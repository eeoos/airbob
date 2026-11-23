package kr.kro.airbob.core.api.controller.v1.accommodation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.api.controller.v1.accommodation.response.AccommodationResponse;
import kr.kro.airbob.core.domain.accommodation.AccommodationQueryService;
import kr.kro.airbob.core.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AccommodationController implements AccommodationApi {

    private final AccommodationQueryService accommodationQueryService;

    @Override
    public ResponseEntity<ApiResponse<AccommodationResponse.DetailInfo>> getAccommodation(
        Long accommodationId,
        Long viewerId) {
        AccommodationResponse.DetailInfo response = AccommodationResponse.DetailInfo.from(
            accommodationQueryService.findAccommodation(accommodationId, viewerId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Override
    public ResponseEntity<ApiResponse<AccommodationResponse.Availability>> getAccommodationAvailability(
        Long accommodationId) {
        AccommodationResponse.Availability response = AccommodationResponse.Availability.from(
            accommodationQueryService.findAccommodationAvailability(accommodationId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
