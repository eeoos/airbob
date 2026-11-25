package kr.kro.airbob.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Coding rules for application code. See docs/development/coding-convention.md ("코드 규칙").
 * {@link CodingRulesFixtureTest} checks that each rule reports the violation it is meant to catch.
 */
@AnalyzeClasses(packages = ArchitectureSlices.ROOT_PACKAGE, importOptions = ImportOption.DoNotIncludeTests.class,
	// Release the imported classes after this class; the default keeps them for the whole test JVM.
	cacheMode = CacheMode.PER_CLASS)
class CodingRulesTest {

	/** Covers @Value as well as @Autowired, @Inject and @Resource fields. */
	@ArchTest
	static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION
		.because("dependencies and configuration values should be constructor parameters");

	/**
	 * The library condition misses printStackTrace() called through a subclass such as IllegalStateException
	 * (see {@link CodingRulesFixtureTest}), so any no-argument printStackTrace() call is reported as well.
	 */
	@ArchTest
	static final ArchRule noStandardStreams = noClasses()
		.should(ACCESS_STANDARD_STREAMS)
		.orShould().callMethodWhere(printsAStackTrace())
		.because("output should go through the logger");

	@ArchTest
	static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS
		.because("errors should use a specific exception type, usually a BaseException with an ErrorCode");

	@ArchTest
	static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING
		.because("logging goes through SLF4J");

	@ArchTest
	static final ArchRule currentTimeComesFromClock = noClasses()
		.should().callMethodWhere(readsTheSystemClock())
		.because("tests replace the Clock bean to control time; System.nanoTime() for elapsed time is allowed");

	private static DescribedPredicate<JavaMethodCall> printsAStackTrace() {
		return DescribedPredicate.describe("print a stack trace",
			call -> call.getTarget().getName().equals("printStackTrace")
				&& call.getTarget().getRawParameterTypes().isEmpty());
	}

	/** java.time {@code now()} or {@code now(ZoneId)}, and {@code System.currentTimeMillis()}. */
	private static DescribedPredicate<JavaMethodCall> readsTheSystemClock() {
		return DescribedPredicate.describe("read the current time without the Clock bean", call -> {
			JavaClass owner = call.getTargetOwner();
			String name = call.getTarget().getName();
			if (owner.getName().equals("java.lang.System")) {
				return name.equals("currentTimeMillis");
			}
			List<String> parameters = call.getTarget().getRawParameterTypes().stream().map(JavaClass::getName).toList();
			return owner.getPackageName().equals("java.time") && name.equals("now")
				&& !parameters.equals(List.of("java.time.Clock"));
		});
	}
}
