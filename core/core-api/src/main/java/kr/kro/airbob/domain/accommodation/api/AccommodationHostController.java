package kr.kro.airbob.domain.accommodation.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.service.AccommodationCommandService;
import kr.kro.airbob.domain.accommodation.service.AccommodationImageService;
import kr.kro.airbob.domain.accommodation.service.AccommodationQueryService;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AccommodationHostController implements AccommodationHostApi {

    private final AccommodationCommandService accommodationCommandService;
    private final AccommodationImageService accommodationImageService;
    private final AccommodationQueryService accommodationQueryService;

    @Override
    public ResponseEntity<ApiResponse<AccommodationResponse.Create>> registerAccommodation(
        Long memberId) {
        AccommodationResponse.Create response = accommodationCommandService.createAccommodation(memberId);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> updateAccommodation(Long accommodationId,
        AccommodationRequest.Update request,
        Long memberId) {
        accommodationCommandService.updateAccommodation(accommodationId, request, memberId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> publishAccommodation(Long accommodationId,
        Long memberId) {
        accommodationCommandService.publishAccommodation(accommodationId, memberId);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> unpublishAccommodation(Long accommodationId,
        Long memberId) {
        accommodationCommandService.unpublishAccommodation(accommodationId, memberId);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> deleteAccommodation(Long accommodationId,
        Long memberId) {
        accommodationCommandService.deleteAccommodation(accommodationId, memberId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    public ResponseEntity<ApiResponse<ImageResponse.ImageUploadResult>> uploadAccommodationImages(
        Long accommodationId,
        List<MultipartFile> images,
        Long memberId) {
        ImageResponse.ImageUploadResult response = accommodationImageService.uploadImages(accommodationId, images,
            memberId);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> deleteAccommodationImage(
        Long accommodationId,
        Long imageId,
        Long memberId) {
        accommodationImageService.deleteImage(accommodationId, imageId, memberId);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    public ResponseEntity<ApiResponse<AccommodationResponse.HostAccommodationInfos>> getHostAccommodations(
        CursorRequest.CursorPageRequest request,
        AccommodationStatus status,
        Long memberId) {
        AccommodationResponse.HostAccommodationInfos response = accommodationQueryService.findMyAccommodations(
            memberId, request, status);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Override
    public ResponseEntity<ApiResponse<AccommodationResponse.HostDetail>> getHostAccommodationDetail(
        Long accommodationId,
        Long hostId) {
        AccommodationResponse.HostDetail response = accommodationQueryService.findHostAccommodationDetail(
            accommodationId, hostId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
