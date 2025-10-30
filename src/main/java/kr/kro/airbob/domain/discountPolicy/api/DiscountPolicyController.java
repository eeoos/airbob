package kr.kro.airbob.domain.discountPolicy.api;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.discountPolicy.service.DiscountPolicyService;
import kr.kro.airbob.domain.discountPolicy.dto.DiscountPolicyRequest;
import kr.kro.airbob.domain.discountPolicy.dto.DiscountPolicyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class DiscountPolicyController {

    private final DiscountPolicyService discountpolicyService;

    @GetMapping("/v1/discount")
    public ResponseEntity<ApiResponse<DiscountPolicyResponse.DiscountPolicyInfos>> findValidDiscountPolicies() {
        DiscountPolicyResponse.DiscountPolicyInfos discountPolicies = discountpolicyService.findValidDiscountPolicies();
        return ResponseEntity.ok(ApiResponse.success(discountPolicies));
    }

    @PostMapping("/v1/discount")
    public ResponseEntity<ApiResponse<Void>> createDiscountPolicy(@RequestBody DiscountPolicyRequest.Create discountPolicyCreateDto) {
        discountpolicyService.createDiscountPolicy(discountPolicyCreateDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
    }

    @PatchMapping("/v1/discount/{discountPolicyId}")
    public ResponseEntity<ApiResponse<Void>> updateDiscountPolicy(@RequestBody DiscountPolicyRequest.Update discountPolicyUpdateDto, @PathVariable Long discountPolicyId){
        discountpolicyService.updateDiscountPolicy(discountPolicyUpdateDto, discountPolicyId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @DeleteMapping("/v1/discount/{discountPolicyId}")
    public ResponseEntity<ApiResponse<Void>> deleteDiscountPolicy(@PathVariable Long discountPolicyId){
        discountpolicyService.deletePolicy(discountPolicyId);
        return ResponseEntity.ok(ApiResponse.success());
    }

}
