package kr.kro.airbob.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.Optional;
import java.util.Set;

import org.springframework.boot.test.context.TestConfiguration;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Naming rules for core-api test code. See docs/development/coding-convention.md ("이름").
 * The violation fixtures of {@link NamingRulesFixtureTest} are left out. Test method names are a Spotless lint
 * (build.gradle), which also covers the other modules and API-documentation-only changes.
 */
@AnalyzeClasses(packages = ArchitectureSlices.ROOT_PACKAGE, importOptions = ImportOption.OnlyIncludeTests.class,
	// Release the imported classes after this class; the default keeps them for the whole test JVM.
	cacheMode = CacheMode.PER_CLASS)
class TestNamingRulesTest {

	private static final Set<String> TEST_ANNOTATIONS = Set.of(
		"org.junit.jupiter.api.Test",
		"org.junit.jupiter.api.RepeatedTest",
		"org.junit.jupiter.api.TestFactory",
		"org.junit.jupiter.api.TestTemplate",
		"org.junit.jupiter.params.ParameterizedTest");

	@ArchTest
	static final ArchRule testClassNaming = testClassNamingIn(notNamingFixtures());

	@ArchTest
	static final ArchRule testConfigurationNaming = classes()
		.that().areAnnotatedWith(TestConfiguration.class).and(notNamingFixtures())
		.should().haveSimpleNameEndingWith("Configuration")
		.as("@TestConfiguration classes should be named *Configuration");

	@ArchTest
	static final ArchRule abbreviations = NamingRulesTest.abbreviationsIn(notNamingFixtures());

	/**
	 * A class that declares test methods ends with Test, and so does every class around it: the test class around
	 * @Nested classes is checked even when only the @Nested classes declare test methods.
	 */
	static ArchRule testClassNamingIn(DescribedPredicate<? super JavaClass> scope) {
		return classes()
			.that(declareTestMethods()).and(scope)
			.should(beNamedTestWithTheClassesAroundThem())
			.as("classes that declare test methods, and the classes around them, should be named *Test");
	}

	private static ArchCondition<JavaClass> beNamedTestWithTheClassesAroundThem() {
		return new ArchCondition<>("be named *Test, with the classes around them") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				Optional<JavaClass> current = Optional.of(javaClass);
				while (current.isPresent()) {
					JavaClass testClass = current.get();
					boolean named = testClass.getSimpleName().endsWith("Test");
					events.add(new SimpleConditionEvent(testClass, named, testClass.getName()
						+ (named ? " is named *Test" : " holds test methods but is not named *Test")));
					current = testClass.getEnclosingClass();
				}
			}
		};
	}

	private static DescribedPredicate<JavaClass> declareTestMethods() {
		return DescribedPredicate.describe("declare test methods", javaClass -> javaClass.getMethods().stream()
			.anyMatch(method -> TEST_ANNOTATIONS.stream().anyMatch(method::isAnnotatedWith)));
	}

	private static DescribedPredicate<JavaClass> notNamingFixtures() {
		return DescribedPredicate.describe("are not naming rule fixtures", javaClass -> !NamingRulesTest
			.topLevelOf(javaClass).getName().equals(NamingRulesFixtureTest.class.getName()));
	}
}
