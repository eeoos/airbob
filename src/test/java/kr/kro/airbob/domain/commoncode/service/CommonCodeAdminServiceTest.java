package kr.kro.airbob.domain.commoncode.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupRequest;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeRequest;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeDuplicateException;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeGroupDuplicateException;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeGroupRepository;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("공통 코드 관리 서비스 단위 테스트")
class CommonCodeAdminServiceTest {

	@Mock private CommonCodeGroupRepository groupRepository;
	@Mock private CommonCodeRepository commonCodeRepository;
	@InjectMocks private CommonCodeAdminService adminService;

	@Test
	@DisplayName("그룹 INSERT 중 중복 키가 발생하면 CC004 예외로 변환한다")
	void translateDuplicateKeyOnCreateGroup() {
		given(groupRepository.existsById("RACE_GROUP")).willReturn(false);
		willThrow(new DataIntegrityViolationException("duplicate key"))
			.given(groupRepository).insert(any());

		assertThatThrownBy(() -> adminService.createGroup(
			new CommonCodeGroupRequest.Create("race_group", "경쟁 그룹", null, true)))
			.isInstanceOf(CommonCodeGroupDuplicateException.class);
	}

	@Test
	@DisplayName("코드 INSERT 중 중복 키가 발생하면 CC003 예외로 변환한다")
	void translateDuplicateKeyOnCreateCode() {
		given(groupRepository.existsById("AMENITY_TYPE")).willReturn(true);
		given(commonCodeRepository.existsById(any())).willReturn(false);
		DataIntegrityViolationException duplicateKey =
			new DataIntegrityViolationException("duplicate key");
		willThrow(duplicateKey)
			.given(commonCodeRepository).insert(any());

		assertThatThrownBy(() -> adminService.create("AMENITY_TYPE",
			new CommonCodeRequest.Create("WIFI", "무선 인터넷", null, 1, true)))
			.isInstanceOf(CommonCodeDuplicateException.class)
			.hasCause(duplicateKey);
	}
}
