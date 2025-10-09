package kr.kro.airbob.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ApiResponse<T> {

	private final boolean success;
	private final T data;
	private final ErrorResponse error;

	// 성공
	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(true, data, null);
	}

	// 성공(데이터 X)
	public static ApiResponse<?> success() {
		return new ApiResponse<>(true, null, null);
	}

	// 실패
	public static ApiResponse<?> error(ErrorResponse errorResponse) {
		return new ApiResponse<>(false, null, errorResponse);
	}
}
