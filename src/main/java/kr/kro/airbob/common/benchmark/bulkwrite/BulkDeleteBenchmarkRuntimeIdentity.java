package kr.kro.airbob.common.benchmark.bulkwrite;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("bulk-delete-benchmark")
public class BulkDeleteBenchmarkRuntimeIdentity {
	public static final String HEADER_NAME = "X-Bulk-Delete-Runtime-Id";
	private final String value = UUID.randomUUID().toString();

	public String value() {
		return value;
	}
}
