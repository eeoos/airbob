package kr.kro.airbob.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import kr.kro.airbob.core.api.config.ClockConfiguration;

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
	@ValueSource(classes = {PrintsToStandardOut.class, PrintsStackTrace.class, PrintsStackTraceByReference.class})
	void standardStreamRuleReportsConsoleOutput(Class<?> fixture) {
		assertThat(hasViolation(CodingRulesTest.noStandardStreams, fixture)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(classes = {ThrowsRuntimeException.class, RuntimeExceptionByReference.class})
	void genericExceptionRuleReportsRuntimeException(Class<?> fixture) {
		assertThat(hasViolation(CodingRulesTest.noGenericExceptions, fixture)).isTrue();
	}

	@Test
	void genericExceptionRuleAllowsSpecificExceptions() {
		assertThat(hasViolation(CodingRulesTest.noGenericExceptions, SpecificException.class)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(classes = {JavaUtilLoggingField.class, JavaUtilLoggingWithoutField.class})
	void javaUtilLoggingRuleReportsItsLogger(Class<?> fixture) {
		assertThat(hasViolation(CodingRulesTest.noJavaUtilLogging, fixture)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(classes = {CurrentTimeMillis.class, NowWithoutArguments.class, NowWithZone.class,
		NowByReference.class, CurrentTimeMillisByReference.class, SystemClockOutsideConfig.class,
		SystemInstantSource.class, TickingSystemClock.class})
	void clockRuleReportsReadingTheSystemClock(Class<?> fixture) {
		assertThat(hasViolation(CodingRulesTest.currentTimeComesFromClock, fixture)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(classes = {ReadsTheClockBean.class, ClockConfiguration.class})
	void clockRuleAllowsTheClockBeanAndElapsedTime(Class<?> fixture) {
		assertThat(hasViolation(CodingRulesTest.currentTimeComesFromClock, fixture)).isFalse();
	}

	@Test
	void entitySetterRuleReportsPublicSetters() {
		ArchRule rule = CodingRulesTest.noPublicSettersInClassesAnnotatedWith(FixtureEntity.class.getName());

		assertThat(hasViolation(rule, SettableEntity.class)).isTrue();
		assertThat(hasViolation(rule, ConfirmableEntity.class)).isFalse();
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

	static class PrintsStackTraceByReference {
		void print(List<Throwable> errors) {
			errors.forEach(Throwable::printStackTrace);
		}
	}

	static class ThrowsRuntimeException {
		void fail() {
			throw new RuntimeException("fixture");
		}
	}

	static class RuntimeExceptionByReference {
		Object require(Optional<Object> value) {
			return value.orElseThrow(RuntimeException::new);
		}
	}

	static class SpecificException extends RuntimeException {
		SpecificException() {
			super("fixture");
		}
	}

	static class JavaUtilLoggingField {
		final Logger logger = Logger.getLogger("fixture");
	}

	static class JavaUtilLoggingWithoutField {
		void log() {
			Logger.getGlobal().info("fixture");
		}
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

	static class NowByReference {
		Instant now(Optional<Instant> given) {
			return given.orElseGet(Instant::now);
		}
	}

	static class CurrentTimeMillisByReference {
		long now(OptionalLong given) {
			return given.orElseGet(System::currentTimeMillis);
		}
	}

	static class SystemClockOutsideConfig {
		Instant now() {
			return Clock.systemUTC().instant();
		}
	}

	static class SystemInstantSource {
		Instant now() {
			return InstantSource.system().instant();
		}
	}

	static class TickingSystemClock {
		Clock clock(ZoneId zone) {
			return Clock.tickSeconds(zone);
		}
	}

	static class ReadsTheClockBean {
		LocalDate today(Clock clock) {
			return LocalDate.now(clock);
		}

		Clock ticking(Clock clock) {
			return Clock.tick(clock, Duration.ofSeconds(1));
		}

		long millis(Clock clock) {
			return clock.millis();
		}

		long elapsedSince(long startNanos) {
			return System.nanoTime() - startNanos;
		}
	}

	/** 실제 @Entity를 쓰면 JPA 엔티티 스캔에 잡히므로 픽스처 전용 어노테이션을 쓴다. */
	@interface FixtureEntity{}

	@FixtureEntity
	static class SettableEntity {
		private String name;

		public void setName(String name) {
			this.name = name;
		}
	}

	@FixtureEntity
	static class ConfirmableEntity {
		private boolean confirmed;

		public void confirm() {
			confirmed = true;
		}
	}
}
