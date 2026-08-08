package kr.kro.airbob.domain.commoncode.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.commoncode.dto.CommonCodeAdminResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeRequest;
import kr.kro.airbob.domain.commoncode.entity.CommonCode;
import kr.kro.airbob.domain.commoncode.entity.CommonCodeId;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeDuplicateException;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeGroupNotFoundException;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeNotFoundException;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeGroupRepository;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeRepository;
import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 관리(쓰기) 서비스. 운영자가 배포 없이 코드를 추가/수정한다.
 * 조회 캐시는 인스턴스별 TTL 만료 후 DB 변경을 반영한다.
 */
@Service
@RequiredArgsConstructor
public class CommonCodeAdminService {

	private final CommonCodeGroupRepository groupRepository;
	private final CommonCodeRepository commonCodeRepository;

	@Transactional(readOnly = true)
	public List<CommonCodeAdminResponse> getAll(String groupCode) {
		requireGroup(groupCode);
		return commonCodeRepository.findByGroupCodeOrderBySortOrderAsc(groupCode).stream()
			.map(CommonCodeAdminResponse::from)
			.toList();
	}

	@Transactional
	public CommonCodeAdminResponse create(String groupCode, CommonCodeRequest.Create request) {
		requireGroup(groupCode);

		String code = request.code().toUpperCase();
		if (commonCodeRepository.existsById(new CommonCodeId(groupCode, code))) {
			throw new CommonCodeDuplicateException();
		}

		CommonCode commonCode = CommonCode.builder()
			.groupCode(groupCode)
			.code(code)
			.name(request.name())
			.description(request.description())
			.sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
			.active(request.isActive() == null || request.isActive())
			.build();
		commonCodeRepository.save(commonCode);

		return CommonCodeAdminResponse.from(commonCode);
	}

	@Transactional
	public CommonCodeAdminResponse update(String groupCode, String code, CommonCodeRequest.Update request) {
		CommonCode commonCode = commonCodeRepository.findById(new CommonCodeId(groupCode, code.toUpperCase()))
			.orElseThrow(CommonCodeNotFoundException::new);

		commonCode.updateDisplay(request.name(), request.description(), request.sortOrder(), request.isActive());

		return CommonCodeAdminResponse.from(commonCode);
	}

	private void requireGroup(String groupCode) {
		if (!groupRepository.existsById(groupCode)) {
			throw new CommonCodeGroupNotFoundException();
		}
	}
}
