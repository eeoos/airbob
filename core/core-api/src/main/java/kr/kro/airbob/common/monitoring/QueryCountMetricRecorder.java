package kr.kro.airbob.common.monitoring;

public interface QueryCountMetricRecorder {

	void record(QueryCountSnapshot snapshot);
}
