package kr.kro.airbob.search.infrastructure.elasticsearch;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("accommodationIndexAliasReadiness")
public class AccommodationIndexAliasReadiness implements InitializingBean {

	private final AccommodationIndexAliasBootstrap bootstrap;
	private final boolean bootstrapEnabled;
	private final boolean listenerEnabled;
	private volatile boolean ready;

	public AccommodationIndexAliasReadiness(
		AccommodationIndexAliasBootstrap bootstrap,
		@Value("${accommodation.indexing.bootstrap.enabled:true}") boolean bootstrapEnabled,
		@Value("${accommodation.indexing.kafka.auto-startup:true}") boolean listenerEnabled
	) {
		if (listenerEnabled && !bootstrapEnabled) {
			throw new IllegalArgumentException(
				"Accommodation indexing listener requires alias bootstrap readiness.");
		}
		this.bootstrap = bootstrap;
		this.bootstrapEnabled = bootstrapEnabled;
		this.listenerEnabled = listenerEnabled;
	}

	@Override
	public void afterPropertiesSet() {
		if (!bootstrapEnabled) {
			return;
		}
		bootstrap.ensureReady();
		ready = true;
	}

	public boolean shouldAutoStart() {
		return listenerEnabled && ready;
	}
}
