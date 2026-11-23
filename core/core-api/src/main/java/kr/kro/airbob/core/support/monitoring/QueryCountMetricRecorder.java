package kr.kro.airbob.core.support.monitoring;

public interface QueryCountMetricRecorder {

	void record(QueryCountSnapshot snapshot);
}
