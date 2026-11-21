package kr.kro.airbob.domain.reservation.inventory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 예약 기능을 제공하는 운영 profile에서 inventory lifecycle 비활성화를 막는다.
 */
@Component
@Profile({"aws", "oci"})
public class AccommodationInventoryProductionProfileGuard implements InitializingBean {

	private static final String ERROR_MESSAGE =
		"AWS/OCI reservation deployments require inventory startup and rolling seed";

	private final boolean startupEnabled;
	private final boolean seedEnabled;

	public AccommodationInventoryProductionProfileGuard(
		@Value("${reservation.inventory.startup.enabled:true}") boolean startupEnabled,
		@Value("${reservation.inventory.seed.enabled:true}") boolean seedEnabled
	) {
		this.startupEnabled = startupEnabled;
		this.seedEnabled = seedEnabled;
	}

	@Override
	public void afterPropertiesSet() {
		if (!startupEnabled || !seedEnabled) {
			throw new IllegalStateException(ERROR_MESSAGE);
		}
	}
}
