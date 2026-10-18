package kr.kro.airbob.domain.reservation.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

@Tag(name = "예약")
@RequestMapping("/api")
public interface ReservationGuestApi {

	@Operation(operationId = "Reservation.getGuestReservationDetail", summary = "내 예약 상세 조회",
		description = """
			로그인한 게스트 본인의 예약 상세를 조회합니다. 해석할 수 없는 예약 UID, 없는 예약, 다른 회원의 예약은 모두 404/R001입니다.

			만료된 결제 대기는 정리 작업 전에도 status=EXPIRED, payment_allowed=false로 응답합니다. 이때 hold_expires_at에는 이미 지난 보유 기한이 남아 있을 수 있으므로 기한의 존재만으로 결제 가능 여부를 판단하지 않습니다.

			체크인·체크아웃 일시는 숙소 현지 시간이며 time_zone_id와 함께 읽습니다. payment는 결제 기록이 없으면 null입니다. can_write_review는 숙소가 게시 중이고, 체크아웃한 CONFIRMED·CANCELLATION_FAILED 예약이며 해당 숙소에 게시된 본인 리뷰가 없을 때 true입니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.RESERVATION_NOT_FOUND
	})
	@GetMapping("/v1/profile/guest/reservations/{reservationUid}")
	ResponseEntity<ApiResponse<ReservationResponse.GuestDetail>> getGuestReservationDetail(
		@Parameter(description = "예약 생성 응답의 reservation_uid", example = "00000000-0000-0000-0000-000000000020")
		@PathVariable String reservationUid,
		@CurrentMemberId Long memberId);

	@Operation(operationId = "Reservation.getGuestReservations", summary = "내 예약 목록 조회",
		description = """
			본인의 예약을 생성 시각·ID 내림차순으로 조회합니다. filterType을 생략하면 모든 상태를 포함합니다.

			UPCOMING은 만료 전 결제 대기, 결제 처리 중, 체크아웃 전의 CONFIRMED·CANCELLATION_PENDING·CANCELLATION_FAILED입니다. PAST는 이 세 상태 중 체크아웃 시각이 지난 예약이며, CANCELLED는 취소·만료와 기한이 지난 결제 대기를 포함합니다.

			만료된 결제 대기는 정리 작업 전에도 EXPIRED로 응답합니다. filterType을 바꾸면 cursor를 초기화합니다. 요청 쿼리 이름은 filterType이며 filter_type이 아닙니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_TYPE_VALUE
	})
	@GetMapping("/v1/profile/guest/reservations")
	ResponseEntity<ApiResponse<ReservationResponse.GuestReservationInfos>> getGuestReservations(
		@CursorParam CursorRequest.CursorPageRequest request,
		@Parameter(description = "예약 목록 필터. 생략 시 전체이며 게스트·호스트의 포함 상태는 API 설명을 따릅니다.")
		@RequestParam(required = false) ReservationFilterType filterType,
		@CurrentMemberId Long memberId);
}
