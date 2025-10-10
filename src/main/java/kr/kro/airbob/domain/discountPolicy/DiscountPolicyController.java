package kr.kro.airbob.domain.discountPolicy;

import kr.kro.airbob.domain.discountPolicy.dto.request.DiscountPolicyCreateDto;
import kr.kro.airbob.domain.discountPolicy.dto.request.DiscountPolicyUpdateDto;
import kr.kro.airbob.domain.discountPolicy.dto.response.DiscountPolicyResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class DiscountPolicyController {

    private final DiscountPolicyService discountpolicyService;

    @GetMapping("/v1/discount")
    public ResponseEntity<List<DiscountPolicyResponseDto>> findValidDiscountPolicies() {
        List<DiscountPolicyResponseDto> discountPolicies = discountpolicyService.findValidDiscountPolicies();
        return ResponseEntity.ok(discountPolicies);
    }

    @PostMapping("/v1/discount")
    public ResponseEntity<Void> createDiscountPolicy(@RequestBody DiscountPolicyCreateDto discountPolicyCreateDto) {
        discountpolicyService.createDiscountPolicy(discountPolicyCreateDto);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PatchMapping("/v1/discount/{discountPolicyId}")
    public ResponseEntity<Void> updateDiscountPolicy(@RequestBody DiscountPolicyUpdateDto discountPolicyUpdateDto, @PathVariable Long discountPolicyId){
        discountpolicyService.updateDiscountPolicy(discountPolicyUpdateDto, discountPolicyId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/v1/discount/{discountPolicyId}")
    public ResponseEntity<Void> deleteDiscountPolicy(@PathVariable Long discountPolicyId){
        discountpolicyService.deletePolicy(discountPolicyId);
        return ResponseEntity.noContent().build();
    }
}
