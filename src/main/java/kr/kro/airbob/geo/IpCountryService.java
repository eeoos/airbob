package kr.kro.airbob.geo;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import kr.kro.airbob.geo.dto.GeocodeResult;
import kr.kro.airbob.geo.dto.IpInfoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class IpCountryService {

	private final RestClient restClient;

	public IpCountryService(@Qualifier("generalRestClient") RestClient restClient) {
		this.restClient = restClient;
	}

	@Value("${ipinfo.api.token}")
	private String ipinfoToken;

	public Optional<GeocodeResult> getCountryFromIp(String ip) {
		try {
			String url = String.format("https://ipinfo.io/lite/%s?token=%s", ip, ipinfoToken);

			IpInfoResponse response = restClient.get()
				.uri(url)
				.retrieve()
				.body(IpInfoResponse.class);

			if (response != null) {
				return Optional.of(GeocodeResult.builder()
					.latitude(0.0)
					.longitude(0.0)
					.formattedAddress(String.format("%s", response.country()))
					.success(true)
					.build()
				);
			}
		} catch (Exception e) {
			log.warn("IP 위치 조회 실패: {}", e.getMessage());
		}
		return Optional.empty();
	}
}
