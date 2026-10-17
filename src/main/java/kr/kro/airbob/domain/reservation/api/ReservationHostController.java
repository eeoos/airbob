package kr.kro.airbob.domain.reservation.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;

@Tag(name = "예약")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ReservationHostController {

	private final ReservationService reservationService;

	@Operation(operationId = "Reservation.getHostReservations", summary = "호스트 예약 목록 조회",
		description = """
			현재 본인이 소유한 숙소의 예약을 생성 시각·ID 내림차순으로 조회합니다. 숙소가 게시 중단·삭제 상태여도 조회할 수 있습니다. 결제 대기·결제 처리 중은 모든 필터에서 제외합니다.

			filterType을 생략하면 위 두 상태를 제외한 전체를 반환합니다. UPCOMING과 PAST는 CONFIRMED·CANCELLATION_PENDING·CANCELLATION_FAILED를 체크아웃 시각 전·후로 나누며, CANCELLED는 저장된 CANCELLED·EXPIRED 상태입니다.

			filterType을 바꾸면 cursor를 초기화합니다. 요청 쿼리 이름은 filterType이며 filter_type이 아닙니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_TYPE_VALUE
	})
	@GetMapping("/v1/profile/host/reservations")
	public ResponseEntity<ApiResponse<ReservationResponse.HostReservationInfos>> getHostReservations(
		@CursorParam CursorRequest.CursorPageRequest cursorRequest,
		@Parameter(description = "예약 목록 필터. 생략 시 전체이며 게스트·호스트의 포함 상태는 API 설명을 따릅니다.")
		@RequestParam(required = false) ReservationFilterType filterType,
		@CurrentMemberId Long hostId) {

		ReservationResponse.HostReservationInfos response = reservationService.findHostReservations(hostId, cursorRequest, filterType);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(operationId = "Reservation.getHostReservationDetail", summary = "호스트 예약 상세 조회",
		description = """
			예약 숙소의 현재 소유자에게 상세를 반환합니다. 숙소가 게시 중단·삭제 상태이거나 예약이 결제 대기·처리 중이어도 상세 조회는 가능합니다. 호스트 목록과 달리 상태를 제한하지 않습니다.

			status는 저장된 예약 상태입니다. 체크인·체크아웃 일시는 time_zone_id 기준 현지 시간이며 payment는 결제 기록이 없으면 null입니다.

			해석할 수 없는 예약 UID, 없는 예약, 현재 소유자가 아닌 회원의 요청은 모두 404/R001입니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.RESERVATION_NOT_FOUND
	})
	@GetMapping("/v1/profile/host/reservations/{reservationUid}")
	public ResponseEntity<ApiResponse<ReservationResponse.HostDetail>> getHostReservationDetail(
		@Parameter(description = "예약 생성 응답의 reservation_uid", example = "00000000-0000-0000-0000-000000000020")
		@PathVariable String reservationUid,
		@CurrentMemberId Long hostId) {

		ReservationResponse.HostDetail response = reservationService.findHostReservationDetail(reservationUid, hostId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
