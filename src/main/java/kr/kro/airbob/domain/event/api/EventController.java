package kr.kro.airbob.domain.event.api;

import static kr.kro.airbob.domain.event.common.ApplyResult.DUPLICATE;
import static kr.kro.airbob.domain.event.common.ApplyResult.FULL;
import static kr.kro.airbob.domain.event.common.ApplyResult.SUCCESS;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.event.service.EventService;
import kr.kro.airbob.domain.event.common.ApplyResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api")
public class EventController {

    private final EventService eventService;

    private record MemberRequest(Long memberId) {}

    @PostMapping("/v1/event/{eventId}")
    public ResponseEntity<ApiResponse<String>> applyEvent(@PathVariable Long eventId, @RequestBody MemberRequest request) {
        int eventMaxParticipants = eventService.getEventMaxParticipants(eventId);
        ApplyResult applyResult = eventService.applyToEvent(eventId, request.memberId, eventMaxParticipants);

        switch (applyResult) {
            case SUCCESS -> {
                eventService.consumeQueue(eventId);
                return ResponseEntity.ok(ApiResponse.success(SUCCESS.getMessage()));
            }
            case DUPLICATE -> {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.success(DUPLICATE.getMessage()));
            }
            case FULL -> {
                return ResponseEntity.status(HttpStatus.GONE).body(ApiResponse.success(FULL.getMessage()));
            }
            default -> {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.success("알 수 없는 오류입니다."));
            }
        }
    }
}
