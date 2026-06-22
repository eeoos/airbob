package kr.kro.airbob.common.code;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.code.exception.CommonCodeDuplicateException;
import kr.kro.airbob.common.code.exception.CommonCodeGroupNotFoundException;
import kr.kro.airbob.common.code.exception.CommonCodeNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 관리(쓰기) 서비스. 운영자가 배포 없이 코드를 추가/수정한다.
 * 모든 쓰기 후 해당 그룹 캐시를 무효화해 조회/검증 경로에 즉시 반영한다.
 */
@Service
@RequiredArgsConstructor
public class CommonCodeAdminService {

	private final CommonCodeGroupRepository groupRepository;
	private final CommonCodeDetailRepository detailRepository;
	private final CommonCodeService commonCodeService;

	@Transactional(readOnly = true)
	public List<CommonCodeAdminResponse> getAll(String groupCode) {
		requireGroup(groupCode);
		return detailRepository.findByGroupCodeOrderBySortOrderAsc(groupCode).stream()
			.map(CommonCodeAdminResponse::from)
			.toList();
	}

	@Transactional
	public CommonCodeAdminResponse create(String groupCode, CommonCodeRequest.Create request) {
		requireGroup(groupCode);

		String code = request.code().toUpperCase();
		if (detailRepository.existsById(new CommonCodeDetailId(groupCode, code))) {
			throw new CommonCodeDuplicateException();
		}

		CommonCodeDetail detail = CommonCodeDetail.builder()
			.groupCode(groupCode)
			.code(code)
			.name(request.name())
			.description(request.description())
			.sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
			.active(request.isActive() == null || request.isActive())
			.build();
		detailRepository.save(detail);

		commonCodeService.evict(groupCode);
		return CommonCodeAdminResponse.from(detail);
	}

	@Transactional
	public CommonCodeAdminResponse update(String groupCode, String code, CommonCodeRequest.Update request) {
		CommonCodeDetail detail = detailRepository.findById(new CommonCodeDetailId(groupCode, code.toUpperCase()))
			.orElseThrow(CommonCodeNotFoundException::new);

		detail.updateDisplay(request.name(), request.description(), request.sortOrder(), request.isActive());

		commonCodeService.evict(groupCode);
		return CommonCodeAdminResponse.from(detail);
	}

	private void requireGroup(String groupCode) {
		if (!groupRepository.existsById(groupCode)) {
			throw new CommonCodeGroupNotFoundException();
		}
	}
}
