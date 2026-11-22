package kr.kro.airbob.core.domain.commoncode;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.commoncode.repository.CommonCodeGroupRepository;
import kr.kro.airbob.core.domain.commoncode.repository.CommonCodeRepository;
import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 생성 구현 컴포넌트. 사전 중복 검사를 통과한 동시 생성은 INSERT의 중복 키 오류를 같은 중복 예외로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class CommonCodeWriter {

	private final CommonCodeGroupRepository groupRepository;
	private final CommonCodeRepository commonCodeRepository;

	public CommonCodeGroup createGroup(NewCommonCodeGroup newGroup) {
		String groupCode = CommonCodeGroup.normalizeGroupCode(newGroup.groupCode());
		if (groupRepository.existsById(groupCode)) {
			throw new CommonCodeGroupDuplicateException();
		}

		CommonCodeGroup group = CommonCodeGroup.builder()
			.groupCode(groupCode)
			.groupName(newGroup.groupName())
			.description(newGroup.description())
			.active(newGroup.active() == null || newGroup.active())
			.build();
		try {
			groupRepository.insert(group);
		} catch (DataIntegrityViolationException exception) {
			throw new CommonCodeGroupDuplicateException(exception);
		}
		return group;
	}

	/** 그룹이 있는지는 호출자가 먼저 확인한다. */
	public CommonCode createCode(String groupCode, NewCommonCode newCode) {
		String code = CommonCode.normalizeCode(newCode.code());
		if (commonCodeRepository.existsById(new CommonCodeId(groupCode, code))) {
			throw new CommonCodeDuplicateException();
		}

		CommonCode commonCode = CommonCode.builder()
			.groupCode(groupCode)
			.code(code)
			.name(newCode.name())
			.description(newCode.description())
			.sortOrder(newCode.sortOrder() == null ? 0 : newCode.sortOrder())
			.active(newCode.active() == null || newCode.active())
			.build();
		try {
			commonCodeRepository.insert(commonCode);
		} catch (DataIntegrityViolationException exception) {
			throw new CommonCodeDuplicateException(exception);
		}
		return commonCode;
	}
}
