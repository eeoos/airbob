package kr.kro.airbob.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Checks that each rule in {@link CodingRulesTest} reports the violation it is meant to catch. The rules run
 * with resolveMissingDependenciesFromClassPath=false (archunit.properties), so JDK and library types are known
 * only by name; a rule that needs more than the name would pass everything without this check.
 */
class CodingRulesFixtureTest {

	@ParameterizedTest
	@ValueSource(classes = {AutowiredField.class, ValueField.class})
	void fieldInjectionRuleReportsInjectedFields(Class<?> fixture) {
		assertThat(hasViolation(CodingRulesTest.noFieldInjection, fixture)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(classes = {PrintsToStandardOut.class, PrintsStackTrace.class})
	void standardStreamRuleReportsConsoleOutput(Class<?> fixture) {
		assertThat(hasViolation(CodingRulesTest.noStandardStreams, fixture)).isTrue();
	}

	@Test
	void genericExceptionRuleReportsRuntimeException() {
		assertThat(hasViolation(CodingRulesTest.noGenericExceptions, ThrowsRuntimeException.class)).isTrue();
	}

	@Test
	void javaUtilLoggingRuleReportsItsLogger() {
		assertThat(hasViolation(CodingRulesTest.noJavaUtilLogging, UsesJavaUtilLogging.class)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(classes = {CurrentTimeMillis.class, NowWithoutArguments.class, NowWithZone.class})
	void clockRuleReportsReadingTheSystemClock(Class<?> fixture) {
		assertThat(hasViolation(CodingRulesTest.currentTimeComesFromClock, fixture)).isTrue();
	}

	@Test
	void clockRuleAllowsTheClockBeanAndElapsedTime() {
		assertThat(hasViolation(CodingRulesTest.currentTimeComesFromClock, ReadsTheClockBean.class)).isFalse();
	}

	private static boolean hasViolation(ArchRule rule, Class<?> fixture) {
		return rule.evaluate(new ClassFileImporter().importClasses(fixture)).hasViolation();
	}

	static class AutowiredField {
		@Autowired
		Object dependency;
	}

	static class ValueField {
		@Value("${fixture.value}")
		String value;
	}

	static class PrintsToStandardOut {
		void print() {
			System.out.println("fixture");
		}
	}

	static class PrintsStackTrace {
		void print(IllegalStateException exception) {
			exception.printStackTrace();
		}
	}

	static class ThrowsRuntimeException {
		void fail() {
			throw new RuntimeException("fixture");
		}
	}

	static class UsesJavaUtilLogging {
		final Logger logger = Logger.getLogger("fixture");
	}

	static class CurrentTimeMillis {
		long now() {
			return System.currentTimeMillis();
		}
	}

	static class NowWithoutArguments {
		Instant now() {
			return Instant.now();
		}
	}

	static class NowWithZone {
		LocalDateTime now() {
			return LocalDateTime.now(ZoneOffset.UTC);
		}
	}

	static class ReadsTheClockBean {
		LocalDate today(Clock clock) {
			return LocalDate.now(clock);
		}

		long millis(Clock clock) {
			return clock.millis();
		}

		long elapsedSince(long startNanos) {
			return System.nanoTime() - startNanos;
		}
	}
}
