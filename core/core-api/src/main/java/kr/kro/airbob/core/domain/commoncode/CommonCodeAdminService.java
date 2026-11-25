package kr.kro.airbob.core.domain.commoncode;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 관리(쓰기) 서비스. 운영자가 배포 없이 코드를 추가/수정
 * 조회 캐시는 인스턴스별 TTL 만료 후 DB 변경을 반영
 */
@Service
@RequiredArgsConstructor
public class CommonCodeAdminService {

	private final CommonCodeReader commonCodeReader;
	private final CommonCodeWriter commonCodeWriter;

	@Transactional(readOnly = true)
	public List<CommonCodeGroupDetail> getGroups() {
		return commonCodeReader.readGroups().stream()
			.map(CommonCodeGroupDetail::from)
			.toList();
	}

	@Transactional
	public CommonCodeGroupDetail createGroup(NewCommonCodeGroup newGroup) {
		return CommonCodeGroupDetail.from(commonCodeWriter.createGroup(newGroup));
	}

	@Transactional
	public CommonCodeGroupDetail updateGroup(String groupCode, CommonCodeGroupPatch patch) {
		CommonCodeGroup group = commonCodeReader.readGroup(groupCode);
		group.updateDisplay(patch.groupName(), patch.description(), patch.active());
		return CommonCodeGroupDetail.from(group);
	}

	@Transactional(readOnly = true)
	public List<CommonCodeDetail> getAll(String groupCode) {
		requireGroupExists(groupCode);
		return commonCodeReader.readAllCodes(groupCode).stream()
			.map(CommonCodeDetail::from)
			.toList();
	}

	@Transactional
	public CommonCodeDetail create(String groupCode, NewCommonCode newCode) {
		requireGroupExists(groupCode);
		return CommonCodeDetail.from(commonCodeWriter.createCode(groupCode, newCode));
	}

	@Transactional
	public CommonCodeDetail update(String groupCode, String code, CommonCodePatch patch) {
		CommonCode commonCode = commonCodeReader.readCode(groupCode, code);
		commonCode.updateDisplay(patch.name(), patch.description(), patch.sortOrder(), patch.active());
		return CommonCodeDetail.from(commonCode);
	}

	private void requireGroupExists(String groupCode) {
		if (!commonCodeReader.existsGroup(groupCode)) {
			throw new CommonCodeGroupNotFoundException();
		}
	}
}
