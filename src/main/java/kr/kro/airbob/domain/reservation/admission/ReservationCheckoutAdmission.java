package kr.kro.airbob.domain.reservation.admission;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import kr.kro.airbob.domain.reservation.exception.ReservationInventoryBusyException;

@Component
public class ReservationCheckoutAdmission {
	private static final int DEFAULT_HIKARI_MAXIMUM_POOL_SIZE = 10;

	private final Semaphore permits;

	public ReservationCheckoutAdmission(
		DataSource dataSource,
		ReservationCheckoutAdmissionProperties properties
	) {
		Objects.requireNonNull(dataSource, "dataSource must not be null");
		Objects.requireNonNull(properties, "properties must not be null");
		validatePoolCapacity(
			properties.maxConcurrency(),
			DataSourceUnwrapper.unwrap(dataSource, HikariDataSource.class)
		);
		this.permits = new Semaphore(properties.maxConcurrency());
	}

	public <T> T execute(Supplier<T> checkout) {
		Objects.requireNonNull(checkout, "checkout must not be null");
		if (!permits.tryAcquire()) {
			throw new ReservationInventoryBusyException();
		}
		try {
			return checkout.get();
		} finally {
			permits.release();
		}
	}

	private static void validatePoolCapacity(
		int maxConcurrency,
		HikariDataSource hikariDataSource
	) {
		if (hikariDataSource == null) {
			return;
		}
		int maximumPoolSize = hikariDataSource.getMaximumPoolSize();
		if (maximumPoolSize <= 0) {
			maximumPoolSize = DEFAULT_HIKARI_MAXIMUM_POOL_SIZE;
		}
		if ((long)maxConcurrency * 2 > maximumPoolSize) {
			throw new IllegalArgumentException(
				"checkout admission must leave at least half of the Hikari pool for other work");
		}
	}
}
