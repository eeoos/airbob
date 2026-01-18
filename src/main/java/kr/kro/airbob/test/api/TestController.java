package kr.kro.airbob.test.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TestController {

	@GetMapping("/v1/test/cpu-burn")
	public String burnCpu(@RequestParam(defaultValue = "1200") long ms) {
		long end = System.nanoTime() + ms * 1_000_000;
		double x = 0;
		while (System.nanoTime() < end) {
			x += Math.sqrt(12345.6789); // 고정 연산으로 일정한 부하
		}
		return "burn " + ms + "ms, x=" + x;
	}
}
