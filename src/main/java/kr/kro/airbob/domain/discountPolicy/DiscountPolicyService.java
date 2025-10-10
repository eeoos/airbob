package kr.kro.airbob.domain.discountPolicy;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.discountPolicy.dto.DiscountPolicyRequest;
import kr.kro.airbob.domain.discountPolicy.dto.DiscountPolicyResponse;
import kr.kro.airbob.domain.discountPolicy.entity.DiscountPolicy;
import kr.kro.airbob.domain.discountPolicy.exception.DiscountNotFoundException;
import kr.kro.airbob.domain.discountPolicy.repository.DiscountPolicyRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DiscountPolicyService {

    private final DiscountPolicyRepository discountPolicyRepository;

    @Transactional(readOnly = true)
    public DiscountPolicyResponse.DiscountPolicyInfos findValidDiscountPolicies() {
        List<DiscountPolicy> discountPolicies = discountPolicyRepository.findByIsActiveTrue();
        List<DiscountPolicyResponse.DiscountPolicyInfo> infos = discountPolicies.stream()
            .map(DiscountPolicyResponse.DiscountPolicyInfo::of)
            .toList();

        return new DiscountPolicyResponse.DiscountPolicyInfos(infos);
    }

    @Transactional
    public void createDiscountPolicy(DiscountPolicyRequest.Create discountPolicyCreateDto) {
        DiscountPolicy discountPolicy = DiscountPolicy.of(discountPolicyCreateDto);

        discountPolicyRepository.save(discountPolicy);
    }

    @Transactional
    public void updateDiscountPolicy(DiscountPolicyRequest.Update discountPolicyUpdateDto, Long discountPolicyId) {
        DiscountPolicy discountPolicy = discountPolicyRepository.findById(discountPolicyId)
                .orElseThrow(DiscountNotFoundException::new);

        discountPolicy.updateWithDto(discountPolicyUpdateDto);
    }

    @Transactional
    public void deletePolicy(Long discountPolicyId) {
        DiscountPolicy discountPolicy = discountPolicyRepository.findById(discountPolicyId)
                .orElseThrow(DiscountNotFoundException::new);

        discountPolicyRepository.delete(discountPolicy);
    }
}
