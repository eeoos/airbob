package kr.kro.airbob.domain.accommodation.api;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.kro.airbob.domain.accommodation.service.AccommodationService;
import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse.AccommodationSearchResponseDto;
import kr.kro.airbob.domain.auth.AuthService;
import kr.kro.airbob.domain.auth.common.SessionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/accommodations")
public class AccommodationController {

    private final AccommodationService accommodationService;
    private final AuthService authService;

    //todo 이미지 저장 로직 추가
    @PostMapping
    public ResponseEntity<Map<String, Long>> registerAccommodation(@RequestBody @Valid AccommodationRequest.CreateAccommodationDto requestDto,
                                                                   HttpServletRequest request){
        String sessionId = SessionUtil.getSessionIdByCookie(request);
        authService.validateHost(sessionId, requestDto.getHostId());

        Long savedAccommodationId = accommodationService.createAccommodation(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(Map.of("id", savedAccommodationId));
    }

    @PatchMapping("/{accommodationId}")
    @ResponseStatus(HttpStatus.OK)
    public void updateAccommodation(@PathVariable Long accommodationId, @RequestBody AccommodationRequest.UpdateAccommodationDto request){
        accommodationService.updateAccommodation(accommodationId, request);
    }

    @DeleteMapping("/{accommodationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccommodation(@PathVariable Long accommodationId) {
        accommodationService.deleteAccommodation(accommodationId);
    }

    @GetMapping
    public ResponseEntity<List<AccommodationResponse.AccommodationSearchResponseDto>> searchAccommodationsByCondition(
            @ModelAttribute AccommodationRequest.AccommodationSearchConditionDto request, Pageable pageable) {
        List<AccommodationSearchResponseDto> result = accommodationService.searchAccommodations(request, pageable);
        return ResponseEntity.ok(result);
    }
}

