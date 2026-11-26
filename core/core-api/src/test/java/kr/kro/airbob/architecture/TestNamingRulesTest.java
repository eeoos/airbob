package kr.kro.airbob.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.Set;

import org.springframework.boot.test.context.TestConfiguration;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
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

	private static final String NESTED = "org.junit.jupiter.api.Nested";
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
	 * Classes that JUnit runs end with Test, and so does the class around a @Nested one. A static nested test class
	 * runs on its own, so the class around it is not a test class.
	 */
	static ArchRule testClassNamingIn(DescribedPredicate<? super JavaClass> scope) {
		return classes()
			.that(runTestMethods()).and(scope)
			.should(beNamedTestWithTheClassesAroundNestedOnes())
			.as("classes that run test methods, and the classes around @Nested ones, should be named *Test");
	}

	private static ArchCondition<JavaClass> beNamedTestWithTheClassesAroundNestedOnes() {
		return new ArchCondition<>("be named *Test, with the classes around @Nested ones") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				JavaClass current = javaClass;
				report(current, events);
				while (current.isAnnotatedWith(NESTED) && current.getEnclosingClass().isPresent()) {
					current = current.getEnclosingClass().get();
					report(current, events);
				}
			}

			private void report(JavaClass testClass, ConditionEvents events) {
				boolean named = testClass.getSimpleName().endsWith("Test");
				events.add(new SimpleConditionEvent(testClass, named, testClass.getName()
					+ (named ? " is named *Test" : " runs or holds test methods but is not named *Test")));
			}
		};
	}

	/** Concrete classes with their own or inherited test methods; abstract classes and interfaces do not run. */
	private static DescribedPredicate<JavaClass> runTestMethods() {
		return DescribedPredicate.describe("run test methods", javaClass -> !javaClass.isInterface()
			&& !javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
			&& javaClass.getAllMethods().stream()
				.anyMatch(method -> TEST_ANNOTATIONS.stream().anyMatch(method::isAnnotatedWith)));
	}

	private static DescribedPredicate<JavaClass> notNamingFixtures() {
		return DescribedPredicate.describe("are not naming rule fixtures", javaClass -> !NamingRulesTest
			.topLevelOf(javaClass).getName().equals(NamingRulesFixtureTest.class.getName()));
	}
}
