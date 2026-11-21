package kr.kro.airbob.geo.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.Set;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;

class TimeZoneRuntimeCompatibilityTest {

	@Test
	void currentJvmPassesFixedPost2026VancouverRulesAndRecordsRuntime() {
		var snapshot = TimeZoneRuntimeCompatibility.verifyRuntimeRules();

		assertThat(snapshot.javaRuntimeVersion()).isNotBlank();
		assertThat(snapshot.javaVendor()).isNotBlank();
		assertThat(snapshot.tzdbVersion()).isNotBlank();
		assertThat(snapshot.vancouverCheckInAt()).isEqualTo(Instant.parse("2026-11-15T22:00:00Z"));
		assertThat(snapshot.vancouverLocalToday()).isEqualTo(LocalDate.of(2026, 11, 15));
	}

	@Test
	void oldVancouverWinterOffsetFailsEvenWhenZoneNameExists() {
		assertThatThrownBy(() -> TimeZoneRuntimeCompatibility.requireCurrentRules(
			ZoneRules.of(ZoneOffset.ofHours(-8)), Set.of("America/Coyhaique")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("obsolete America/Vancouver")
			.hasMessageContaining("2026-11-15T23:00:00Z")
			.hasMessageContaining("2026-11-14");
	}

	@Test
	void missingCoyhaiqueFailsBeforeTimeShapeCanSilentlySkipItsBoundary() {
		assertThatThrownBy(() -> TimeZoneRuntimeCompatibility.requireCurrentRules(
			ZoneRules.of(ZoneOffset.ofHours(-7)), Set.of("America/Vancouver")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("does not support America/Coyhaique");
	}

	@Test
	void everyZoneInRealPinnedBoundaryArchiveMustBeKnownToCurrentJvm() {
		var required = TimeZoneRuntimeCompatibility.readBoundaryZoneIds();

		assertThat(required).contains("America/Coyhaique", "America/Vancouver", "America/Phoenix",
			"Asia/Seoul", "Australia/Adelaide");
		assertThat(required.size()).isGreaterThan(400);
		assertThatCode(() -> TimeZoneRuntimeCompatibility.requireBoundaryCoverage(
			required, ZoneId.getAvailableZoneIds(), "JVM")).doesNotThrowAnyException();
	}

	@Test
	void incompleteTimeShapeIndexFailsRatherThanReportingGlobalCompatibility() {
		assertThatThrownBy(() -> TimeZoneRuntimeCompatibility.requireBoundaryCoverage(
			Set.of("America/Coyhaique", "Asia/Seoul"), Set.of("Asia/Seoul"), "TimeShape index"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("TimeShape index omitted")
			.hasMessageContaining("America/Coyhaique");
	}

	@Test
	void emptyAndDuplicateBoundaryArchivesFailClosed() throws IOException {
		try (var empty = archive()) {
			assertThatThrownBy(() -> TimeZoneRuntimeCompatibility.readBoundaryZoneIds(empty))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("no zone IDs");
		}
		try (var duplicate = archive("Asia/Seoul", "Asia/Seoul")) {
			assertThatThrownBy(() -> TimeZoneRuntimeCompatibility.readBoundaryZoneIds(duplicate))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
		}
	}

	private TarArchiveInputStream archive(String... names) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (TarArchiveOutputStream output = new TarArchiveOutputStream(bytes)) {
			for (String name : names) {
				TarArchiveEntry entry = new TarArchiveEntry(name);
				entry.setSize(1);
				output.putArchiveEntry(entry);
				output.write(1);
				output.closeArchiveEntry();
			}
			output.finish();
		}
		return new TarArchiveInputStream(new ByteArrayInputStream(bytes.toByteArray()));
	}
}
