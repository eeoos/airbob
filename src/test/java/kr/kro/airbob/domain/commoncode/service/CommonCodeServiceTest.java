package kr.kro.airbob.domain.commoncode.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.benmanes.caffeine.cache.Ticker;

import kr.kro.airbob.domain.commoncode.dto.CommonCodeResponse;
import kr.kro.airbob.domain.commoncode.entity.CommonCode;
import kr.kro.airbob.domain.commoncode.entity.CommonCodeGroup;
import kr.kro.airbob.domain.commoncode.exception.CommonCodeGroupNotFoundException;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeGroupRepository;
import kr.kro.airbob.domain.commoncode.repository.CommonCodeRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("공통 코드 서비스 단위 테스트")
class CommonCodeServiceTest {

	@Mock
	private CommonCodeGroupRepository groupRepository;

	@Mock
	private CommonCodeRepository commonCodeRepository;

	private CommonCodeService commonCodeService;
	private TestTicker ticker;

	@BeforeEach
	void setUp() {
		ticker = new TestTicker();
		commonCodeService = CommonCodeService.withTicker(groupRepository, commonCodeRepository, ticker);
	}

	@Test
	@DisplayName("DB에 등록된 활성 그룹은 고정 상수 없이 조회한다")
	void loadDbDefinedGroup() {
		CommonCodeGroup group = CommonCodeGroup.builder()
			.groupCode("PAYMENT_METHOD")
			.groupName("결제 수단")
			.active(true)
			.build();
		CommonCode code = CommonCode.builder()
			.groupCode("PAYMENT_METHOD")
			.code("CARD")
			.name("카드")
			.sortOrder(1)
			.active(true)
			.build();
		when(groupRepository.findById("PAYMENT_METHOD")).thenReturn(Optional.of(group));
		when(commonCodeRepository.findByGroupCodeAndActiveTrueOrderBySortOrderAsc("PAYMENT_METHOD"))
			.thenReturn(List.of(code));

		assertThat(commonCodeService.getCodes("PAYMENT_METHOD"))
			.extracting(CommonCodeResponse::code)
			.containsExactly("CARD");
	}

	@Test
	@DisplayName("존재하지 않는 그룹은 404 상태를 캐시한다")
	void cacheMissingGroup() {
		when(groupRepository.findById("NOT_A_GROUP")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> commonCodeService.getCodes("NOT_A_GROUP"))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);
		assertThatThrownBy(() -> commonCodeService.getCodes("NOT_A_GROUP"))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);

		verify(groupRepository, times(1)).findById("NOT_A_GROUP");
		verifyNoInteractions(commonCodeRepository);
	}

	@Test
	@DisplayName("존재하지 않던 그룹은 TTL 만료 후 DB에서 다시 로딩한다")
	void reloadMissingGroupAfterTtl() {
		CommonCodeGroup group = CommonCodeGroup.builder()
			.groupCode("TTL_GROUP")
			.groupName("TTL 그룹")
			.active(true)
			.build();
		CommonCode code = CommonCode.builder()
			.groupCode("TTL_GROUP")
			.code("NEW_CODE")
			.name("새 코드")
			.sortOrder(1)
			.active(true)
			.build();
		when(groupRepository.findById("TTL_GROUP"))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(group));
		when(commonCodeRepository.findByGroupCodeAndActiveTrueOrderBySortOrderAsc("TTL_GROUP"))
			.thenReturn(List.of(code));

		assertThatThrownBy(() -> commonCodeService.getCodes("TTL_GROUP"))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);

		ticker.advance(Duration.ofSeconds(59));
		assertThatThrownBy(() -> commonCodeService.getCodes("TTL_GROUP"))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);

		ticker.advance(Duration.ofSeconds(2));
		assertThat(commonCodeService.getCodes("TTL_GROUP"))
			.extracting(CommonCodeResponse::code)
			.containsExactly("NEW_CODE");
		verify(groupRepository, times(2)).findById("TTL_GROUP");
	}

	@Test
	@DisplayName("비활성 그룹은 빈 코드 목록을 반환한다")
	void returnEmptyForInactiveGroup() {
		CommonCodeGroup group = CommonCodeGroup.builder()
			.groupCode("INACTIVE_GROUP")
			.groupName("비활성 그룹")
			.active(false)
			.build();
		when(groupRepository.findById("INACTIVE_GROUP")).thenReturn(Optional.of(group));

		assertThat(commonCodeService.getCodes("INACTIVE_GROUP")).isEmpty();
		verifyNoInteractions(commonCodeRepository);
	}

	private static class TestTicker implements Ticker {

		private final AtomicLong nanos = new AtomicLong();

		@Override
		public long read() {
			return nanos.get();
		}

		private void advance(Duration duration) {
			nanos.addAndGet(duration.toNanos());
		}
	}
}
