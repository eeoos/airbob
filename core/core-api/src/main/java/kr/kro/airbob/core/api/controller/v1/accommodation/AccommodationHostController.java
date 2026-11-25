package kr.kro.airbob.core.api.controller.v1.accommodation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import kr.kro.airbob.core.api.controller.v1.accommodation.request.AccommodationRequest;
import kr.kro.airbob.core.api.controller.v1.accommodation.response.AccommodationResponse;
import kr.kro.airbob.core.api.controller.v1.image.response.ImageResponse;
import kr.kro.airbob.core.domain.accommodation.AccommodationCommandService;
import kr.kro.airbob.core.domain.accommodation.AccommodationImageService;
import kr.kro.airbob.core.domain.accommodation.AccommodationQueryService;
import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import kr.kro.airbob.core.domain.image.StoredImage;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.response.ApiResponse;
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
		AccommodationResponse.Create response = new AccommodationResponse.Create(
			accommodationCommandService.createAccommodation(memberId));
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> updateAccommodation(Long accommodationId,
		AccommodationRequest.Update request,
		Long memberId) {
		accommodationCommandService.updateAccommodation(accommodationId, request.toUpdate(), memberId);
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
		List<StoredImage> uploaded = accommodationImageService.uploadImages(accommodationId, images, memberId);
		ImageResponse.ImageUploadResult response = ImageResponse.ImageUploadResult.from(
			uploaded.stream().map(ImageResponse.ImageInfo::from).toList());

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
		AccommodationResponse.HostAccommodationInfos response = AccommodationResponse.HostAccommodationInfos.from(
			accommodationQueryService.findMyAccommodations(memberId, request, status));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<AccommodationResponse.HostDetail>> getHostAccommodationDetail(
		Long accommodationId,
		Long hostId) {
		AccommodationResponse.HostDetail response = AccommodationResponse.HostDetail.from(
			accommodationQueryService.findHostAccommodationDetail(accommodationId, hostId));

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
