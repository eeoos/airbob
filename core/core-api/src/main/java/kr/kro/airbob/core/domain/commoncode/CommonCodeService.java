package kr.kro.airbob.core.domain.commoncode;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 공개 조회 서비스. 그룹의 활성 코드 목록을 인스턴스별 1분 캐시에서 읽는다.
 */
@Service
@RequiredArgsConstructor
public class CommonCodeService {

	private final CommonCodeReader commonCodeReader;

	public List<CommonCodeDetail> getCodes(String groupCode) {
		return commonCodeReader.readActiveCodes(groupCode);
	}
}
