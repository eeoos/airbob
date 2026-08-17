package kr.kro.airbob.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.search.exception.SearchUnavailableException;

@DisplayName("검색 서비스 장애 응답 계약 테스트")
class SearchUnavailableExceptionHandlerTest {

	@Test
	@DisplayName("검색 장애는 HTTP 503과 SE001 오류 응답으로 변환한다")
	void mapsSearchFailureToServiceUnavailableResponse() {
		GlobalExceptionHandler handler = new GlobalExceptionHandler();

		ResponseEntity<ApiResponse<?>> response = handler.handleBaseException(
			new SearchUnavailableException(new IOException("connection refused")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().isSuccess()).isFalse();
		assertThat(response.getBody().getError().getCode()).isEqualTo("SE001");
		assertThat(response.getBody().getError().getStatus()).isEqualTo(503);
	}
}
