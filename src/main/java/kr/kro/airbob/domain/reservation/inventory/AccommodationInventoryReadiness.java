package kr.kro.airbob.domain.reservation.inventory;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 예약 inventory bootstrap이 끝나기 전에는 readiness probe가 트래픽을 열지 않게 한다.
 */
@Component("accommodationInventory")
public class AccommodationInventoryReadiness implements HealthIndicator {

	private final boolean bootstrapRequired;
	private final AtomicBoolean ready;

	public AccommodationInventoryReadiness(
		@Value("${reservation.inventory.startup.enabled:true}") boolean bootstrapRequired,
		Environment environment
	) {
		this.bootstrapRequired = bootstrapRequired;
		boolean explicitReadOnlyBypass = !bootstrapRequired
			&& environment.acceptsProfiles(Profiles.of("test", "performance-lab"));
		this.ready = new AtomicBoolean(explicitReadOnlyBypass);
	}

	void markBootstrapping() {
		if (bootstrapRequired) {
			ready.set(false);
		}
	}

	void markReady() {
		ready.set(true);
	}

	boolean isReady() {
		return ready.get();
	}

	@Override
	public Health health() {
		if (ready.get()) {
			return Health.up()
				.withDetail("bootstrap", bootstrapRequired ? "complete" : "profile-bypass")
				.build();
		}
		return Health.down()
			.withDetail("bootstrap", bootstrapRequired ? "in-progress" : "disabled-without-bypass")
			.build();
	}
}
