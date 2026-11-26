package kr.kro.airbob.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.THROW_GENERIC_EXCEPTIONS;

import java.util.List;
import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorReference;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchRule;

import kr.kro.airbob.core.api.config.ClockConfiguration;

/**
 * Coding rules for application code. See docs/development/coding-convention.md ("코드 규칙").
 * {@link CodingRulesFixtureTest} checks that each rule reports the violation it is meant to catch.
 * The library conditions check direct calls only, so the custom conditions below match accesses, which also
 * cover method and constructor references such as {@code Instant::now} or {@code RuntimeException::new}.
 */
@AnalyzeClasses(packages = ArchitectureSlices.ROOT_PACKAGE, importOptions = ImportOption.DoNotIncludeTests.class,
	// Release the imported classes after this class; the default keeps them for the whole test JVM.
	cacheMode = CacheMode.PER_CLASS)
class CodingRulesTest {

	private static final Set<String> GENERIC_EXCEPTIONS = Set.of(
		"java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException");
	/** Static methods of Clock and InstantSource that create a clock on the system time. */
	private static final Set<String> SYSTEM_CLOCK_FACTORIES = Set.of(
		"system", "systemUTC", "systemDefaultZone", "tickSeconds", "tickMinutes", "tickMillis");

	/** Covers @Value as well as @Autowired, @Inject and @Resource fields. */
	@ArchTest
	static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION
		.because("dependencies and configuration values should be constructor parameters");

	/** The library condition misses printStackTrace() called through a subclass such as IllegalStateException. */
	@ArchTest
	static final ArchRule noStandardStreams = noClasses()
		.should(ACCESS_STANDARD_STREAMS)
		.orShould().accessTargetWhere(printsAStackTrace())
		.because("output should go through the logger");

	@ArchTest
	static final ArchRule noGenericExceptions = noClasses()
		.should(THROW_GENERIC_EXCEPTIONS)
		.orShould().accessTargetWhere(referencesAGenericExceptionConstructor())
		.because("errors should use a specific exception type, usually a BaseException with an ErrorCode");

	/** The library rule only reports fields of java.util.logging types, not Logger.getGlobal().info(...). */
	@ArchTest
	static final ArchRule noJavaUtilLogging = noClasses()
		.should().dependOnClassesThat().resideInAPackage("java.util.logging..")
		.because("logging goes through SLF4J");

	@ArchTest
	static final ArchRule currentTimeComesFromClock = noClasses()
		.should().accessTargetWhere(readsTheSystemClock())
		.because("tests replace the Clock bean to control time; System.nanoTime() for elapsed time is allowed");

	private static DescribedPredicate<JavaAccess<?>> printsAStackTrace() {
		return DescribedPredicate.describe("print a stack trace",
			access -> access.getTarget() instanceof CodeUnitAccessTarget target
				&& target.getName().equals("printStackTrace") && target.getRawParameterTypes().isEmpty());
	}

	/** Constructor calls are covered by THROW_GENERIC_EXCEPTIONS, which allows super(...) in subclasses. */
	private static DescribedPredicate<JavaAccess<?>> referencesAGenericExceptionConstructor() {
		return DescribedPredicate.describe("reference a generic exception constructor",
			access -> access instanceof JavaConstructorReference
				&& GENERIC_EXCEPTIONS.contains(access.getTargetOwner().getName()));
	}

	/**
	 * java.time {@code now()} or {@code now(ZoneId)}, {@code System.currentTimeMillis()}, and a system Clock or
	 * InstantSource created outside ClockConfiguration, which defines the Clock bean.
	 */
	private static DescribedPredicate<JavaAccess<?>> readsTheSystemClock() {
		return DescribedPredicate.describe("read the current time without the Clock bean", access -> {
			if (!(access.getTarget() instanceof CodeUnitAccessTarget target)) {
				return false;
			}
			String owner = target.getOwner().getName();
			if (owner.equals("java.time.Clock") || owner.equals("java.time.InstantSource")) {
				return SYSTEM_CLOCK_FACTORIES.contains(target.getName())
					&& !access.getOriginOwner().getName().equals(ClockConfiguration.class.getName());
			}
			if (owner.equals("java.lang.System")) {
				return target.getName().equals("currentTimeMillis");
			}
			List<String> parameters = target.getRawParameterTypes().stream().map(JavaClass::getName).toList();
			return target.getOwner().getPackageName().equals("java.time") && target.getName().equals("now")
				&& !parameters.equals(List.of("java.time.Clock"));
		});
	}
}
