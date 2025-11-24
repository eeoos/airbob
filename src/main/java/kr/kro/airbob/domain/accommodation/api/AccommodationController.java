package kr.kro.airbob.domain.accommodation.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.service.AccommodationService;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api")
public class AccommodationController {

    private final AccommodationService accommodationService;

    @PostMapping("/v1/accommodations")
    public ResponseEntity<ApiResponse<AccommodationResponse.Create>> registerAccommodation() {

        Long memberId = UserContext.get().id();

        AccommodationResponse.Create response = accommodationService.createAccommodation(memberId);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response));
    }

    @PatchMapping("/v1/accommodations/{accommodationId}")
    public ResponseEntity<ApiResponse<Void>> updateAccommodation(@PathVariable Long accommodationId,
        @RequestBody @Valid AccommodationRequest.Update request) {
        Long memberId = UserContext.get().id();
        accommodationService.updateAccommodation(accommodationId, request, memberId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PatchMapping("/v1/accommodations/{accommodationId}/publish")
    public ResponseEntity<ApiResponse<Void>> publishAccommodation(@PathVariable Long accommodationId) {
        Long memberId = UserContext.get().id();
        accommodationService.publishAccommodation(accommodationId, memberId);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @PatchMapping("/v1/accommodations/{accommodationId}/unpublish")
    public ResponseEntity<ApiResponse<Void>> unpublishAccommodation(@PathVariable Long accommodationId) {
        Long memberId = UserContext.get().id();
        accommodationService.unpublishAccommodation(accommodationId, memberId);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @DeleteMapping("/v1/accommodations/{accommodationId}")
    public ResponseEntity<ApiResponse<Void>> deleteAccommodation(@PathVariable Long accommodationId) {
        Long memberId = UserContext.get().id();
        accommodationService.deleteAccommodation(accommodationId, memberId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/v1/accommodations/{accommodationId}/images")
    public ResponseEntity<ApiResponse<ImageResponse.ImageInfos>> uploadAccommodationImages(
        @PathVariable Long accommodationId,
        @RequestParam("images") List<MultipartFile> images) {

        Long memberId = UserContext.get().id();
        ImageResponse.ImageInfos response = accommodationService.uploadImages(accommodationId, images,
            memberId);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @DeleteMapping("/v1/accommodations/{accommodationId}/images/{imageId}")
    public ResponseEntity<ApiResponse<Void>> deleteAccommodationImage(
        @PathVariable Long accommodationId,
        @PathVariable Long imageId) {

        Long memberId = UserContext.get().id();
        accommodationService.deleteImage(accommodationId, imageId, memberId);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/v1/accommodations/{accommodationId}")
    public ResponseEntity<ApiResponse<AccommodationResponse.DetailInfo>> getAccommodation(
        @PathVariable Long accommodationId) {
        AccommodationResponse.DetailInfo response = accommodationService.findAccommodation(accommodationId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/v1/profile/host/accommodations")
    public ResponseEntity<ApiResponse<AccommodationResponse.HostAccommodationInfos>> getHostAccommodations(
        @CursorParam CursorRequest.CursorPageRequest request,
        @RequestParam(required = false) AccommodationStatus status) {
        Long memberId = UserContext.get().id();
        AccommodationResponse.HostAccommodationInfos response = accommodationService.findMyAccommodations(
            memberId, request, status);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/v1/profile/host/accommodations/{accommodationId}")
    public ResponseEntity<ApiResponse<AccommodationResponse.HostDetail>> getHostAccommodationDetail(
        @PathVariable Long accommodationId) {

        Long hostId = UserContext.get().id();
        AccommodationResponse.HostDetail response = accommodationService.findHostAccommodationDetail(accommodationId, hostId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

