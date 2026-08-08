package kr.kro.airbob.domain.commoncode.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.commoncode.exception.CommonCodeGroupNotFoundException;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeDetailRepository;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeGroupRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("공통 코드 서비스 단위 테스트")
class CommonCodeServiceTest {

	@Mock
	private CommonCodeGroupRepository groupRepository;

	@Mock
	private CommonCodeDetailRepository detailRepository;

	@InjectMocks
	private CommonCodeService commonCodeService;

	@Test
	@DisplayName("지원하지 않는 그룹은 DB 조회 없이 거부한다")
	void rejectUnsupportedGroupBeforeCacheLoad() {
		assertThatThrownBy(() -> commonCodeService.getCodes("NOT_A_GROUP"))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);

		verifyNoInteractions(groupRepository, detailRepository);
	}
}
