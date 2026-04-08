package kr.kro.airbob;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@Disabled("외부 인프라(MySQL, Redis, ES) 의존으로 CI에서 실행 불가 - ReservationConcurrencyTest에서 컨텍스트 로드 검증 대체")
@SpringBootTest
@ActiveProfiles("test")
class AirbobApplicationTests {

	@Test
	void contextLoads() {
	}

}
