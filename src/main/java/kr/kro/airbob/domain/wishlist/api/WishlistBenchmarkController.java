package kr.kro.airbob.domain.wishlist.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.service.WishlistBenchmarkService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Profile("read-model-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/members/wishlists")
public class WishlistBenchmarkController {

	private final WishlistBenchmarkService benchmarkService;
	private final BenchmarkAccessGuard accessGuard;

	@GetMapping
	public ResponseEntity<ApiResponse<WishlistResponse.WishlistInfos>> findWishlistsBefore(
		@CursorParam CursorRequest.CursorPageRequest request,
		@RequestParam(required = false) Long accommodationId,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken
	) {
		accessGuard.verify(benchmarkToken);
		Long memberId = UserContext.get().id();
		WishlistResponse.WishlistInfos response =
			benchmarkService.findWishlistsBefore(request, memberId, accommodationId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
