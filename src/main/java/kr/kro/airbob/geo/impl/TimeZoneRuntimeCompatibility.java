package kr.kro.airbob.geo.impl;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesProvider;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.luben.zstd.ZstdInputStream;

import net.iakovlev.timeshape.TimeZoneEngine;

/** Rejects a JVM that would silently omit TimeShape regions or use obsolete booking rules. */
public final class TimeZoneRuntimeCompatibility {

	private static final Logger log = LoggerFactory.getLogger(TimeZoneRuntimeCompatibility.class);
	private static final String VANCOUVER = "America/Vancouver";
	private static final String COYHAIQUE = "America/Coyhaique";
	private static final LocalDateTime VANCOUVER_CHECK_IN = LocalDateTime.parse("2026-11-15T15:00:00");
	private static final Instant EXPECTED_CHECK_IN = Instant.parse("2026-11-15T22:00:00Z");
	private static final Instant DECISION_AT = Instant.parse("2026-11-15T07:30:00Z");
	private static final LocalDate EXPECTED_LOCAL_TODAY = LocalDate.of(2026, 11, 15);

	private TimeZoneRuntimeCompatibility() {
	}

	static TimeZoneEngine initializeCompatibleEngine() {
		Snapshot snapshot = verifyRuntimeRules();
		SortedSet<String> boundaryZoneIds = readBoundaryZoneIds();
		requireBoundaryCoverage(boundaryZoneIds, ZoneId.getAvailableZoneIds(), "JVM");
		TimeZoneEngine engine = TimeZoneEngine.initialize();
		requireBoundaryCoverage(boundaryZoneIds, engine.getKnownZoneIds().stream()
			.map(ZoneId::getId).collect(Collectors.toSet()), "TimeShape index");
		log.info("Time-zone compatibility verified: javaRuntimeVersion={}, javaVendor={}, tzdbVersion={}, "
				+ "boundaryZoneCount={}, vancouverCheckInAt={}, vancouverLocalToday={}, coyhaiqueSupported=true",
			snapshot.javaRuntimeVersion(), snapshot.javaVendor(), snapshot.tzdbVersion(),
			boundaryZoneIds.size(), snapshot.vancouverCheckInAt(), snapshot.vancouverLocalToday());
		return engine;
	}

	public static Snapshot verifyRuntimeRules() {
		ZoneRules rules = ZoneId.of(VANCOUVER).getRules();
		requireCurrentRules(rules, ZoneId.getAvailableZoneIds());
		return new Snapshot(System.getProperty("java.runtime.version"), System.getProperty("java.vendor"),
			ZoneRulesProvider.getVersions(VANCOUVER).lastKey(),
			VANCOUVER_CHECK_IN.toInstant(rules.getOffset(VANCOUVER_CHECK_IN)),
			LocalDateTime.ofInstant(DECISION_AT, rules.getOffset(DECISION_AT)).toLocalDate());
	}

	static void requireCurrentRules(ZoneRules vancouverRules, Set<String> availableZoneIds) {
		if (!availableZoneIds.contains(COYHAIQUE)) {
			throw new IllegalStateException("JVM time-zone data does not support " + COYHAIQUE
				+ "; install a supported Java 21 patch with current TZDB before starting Airbob");
		}
		Instant actualCheckIn = VANCOUVER_CHECK_IN.toInstant(vancouverRules.getOffset(VANCOUVER_CHECK_IN));
		LocalDate actualToday = LocalDateTime.ofInstant(
			DECISION_AT, vancouverRules.getOffset(DECISION_AT)).toLocalDate();
		if (!EXPECTED_CHECK_IN.equals(actualCheckIn) || !EXPECTED_LOCAL_TODAY.equals(actualToday)) {
			throw new IllegalStateException("JVM time-zone data has obsolete America/Vancouver rules: "
				+ "expected check-in 2026-11-15T22:00:00Z and local date 2026-11-15, got "
				+ actualCheckIn + " and " + actualToday
				+ "; install a supported Java 21 patch with TZDB 2026b or newer");
		}
	}

	static SortedSet<String> readBoundaryZoneIds() {
		try (InputStream resource = TimeZoneEngine.class.getResourceAsStream("/data.tar.zstd")) {
			if (resource == null) {
				throw new IllegalStateException("TimeShape boundary archive is missing");
			}
			try (TarArchiveInputStream archive = new TarArchiveInputStream(
				new BufferedInputStream(new ZstdInputStream(resource)))) {
				return readBoundaryZoneIds(archive);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot verify TimeShape boundary zone IDs", exception);
		}
	}

	static SortedSet<String> readBoundaryZoneIds(TarArchiveInputStream archive) throws IOException {
		SortedSet<String> zoneIds = new TreeSet<>();
		TarArchiveEntry entry;
		while ((entry = archive.getNextEntry()) != null) {
			// TimeShape's pinned archive stores each region under its IANA zone ID.
			if (!entry.isFile() || entry.getName().isBlank() || !zoneIds.add(entry.getName())) {
				throw new IllegalStateException("Unexpected or duplicate TimeShape boundary entry: "
					+ entry.getName());
			}
		}
		if (zoneIds.isEmpty()) {
			throw new IllegalStateException("TimeShape boundary archive contains no zone IDs");
		}
		return zoneIds;
	}

	static void requireBoundaryCoverage(Set<String> requiredZoneIds, Set<String> availableZoneIds,
		String provider) {
		SortedSet<String> missing = new TreeSet<>(requiredZoneIds);
		missing.removeAll(availableZoneIds);
		if (!missing.isEmpty()) {
			throw new IllegalStateException(provider + " omitted required TimeShape boundary zones: " + missing);
		}
	}

	public record Snapshot(String javaRuntimeVersion, String javaVendor, String tzdbVersion,
		Instant vancouverCheckInAt, LocalDate vancouverLocalToday) {
	}
}
