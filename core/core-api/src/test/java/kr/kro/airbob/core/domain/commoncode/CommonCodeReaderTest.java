package kr.kro.airbob.core.domain.commoncode;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

import kr.kro.airbob.core.domain.commoncode.repository.CommonCodeGroupRepository;
import kr.kro.airbob.core.domain.commoncode.repository.CommonCodeRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("공통 코드 Reader 단위 테스트")
class CommonCodeReaderTest {

	@Mock
	private CommonCodeGroupRepository groupRepository;

	@Mock
	private CommonCodeRepository commonCodeRepository;

	private CommonCodeReader commonCodeReader;
	private TestTicker ticker;

	@BeforeEach
	void setUp() {
		ticker = new TestTicker();
		commonCodeReader = CommonCodeReader.withTicker(groupRepository, commonCodeRepository, ticker);
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
		given(groupRepository.findById("PAYMENT_METHOD")).willReturn(Optional.of(group));
		given(commonCodeRepository.findByGroupCodeAndActiveTrueOrderBySortOrderAscCodeAsc("PAYMENT_METHOD"))
			.willReturn(List.of(code));

		assertThat(commonCodeReader.readActiveCodes("PAYMENT_METHOD"))
			.extracting(CommonCodeDetail::code)
			.containsExactly("CARD");
	}

	@Test
	@DisplayName("존재하지 않는 그룹은 404 상태를 캐시한다")
	void cacheMissingGroup() {
		given(groupRepository.findById("NOT_A_GROUP")).willReturn(Optional.empty());

		assertThatThrownBy(() -> commonCodeReader.readActiveCodes("NOT_A_GROUP"))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);
		assertThatThrownBy(() -> commonCodeReader.readActiveCodes("NOT_A_GROUP"))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);

		then(groupRepository).should(times(1)).findById("NOT_A_GROUP");
		then(commonCodeRepository).shouldHaveNoInteractions();
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
		given(groupRepository.findById("TTL_GROUP"))
			.willReturn(Optional.empty())
			.willReturn(Optional.of(group));
		given(commonCodeRepository.findByGroupCodeAndActiveTrueOrderBySortOrderAscCodeAsc("TTL_GROUP"))
			.willReturn(List.of(code));

		assertThatThrownBy(() -> commonCodeReader.readActiveCodes("TTL_GROUP"))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);

		ticker.advance(Duration.ofSeconds(59));
		assertThatThrownBy(() -> commonCodeReader.readActiveCodes("TTL_GROUP"))
			.isInstanceOf(CommonCodeGroupNotFoundException.class);

		ticker.advance(Duration.ofSeconds(2));
		assertThat(commonCodeReader.readActiveCodes("TTL_GROUP"))
			.extracting(CommonCodeDetail::code)
			.containsExactly("NEW_CODE");
		then(groupRepository).should(times(2)).findById("TTL_GROUP");
	}

	@Test
	@DisplayName("비활성 그룹은 빈 코드 목록을 반환한다")
	void returnEmptyForInactiveGroup() {
		CommonCodeGroup group = CommonCodeGroup.builder()
			.groupCode("INACTIVE_GROUP")
			.groupName("비활성 그룹")
			.active(false)
			.build();
		given(groupRepository.findById("INACTIVE_GROUP")).willReturn(Optional.of(group));

		assertThat(commonCodeReader.readActiveCodes("INACTIVE_GROUP")).isEmpty();
		then(commonCodeRepository).shouldHaveNoInteractions();
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
