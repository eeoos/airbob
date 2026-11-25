package kr.kro.airbob.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchRule;

/** Coding rules for test code. See docs/development/coding-convention.md ("코드 규칙"). */
@AnalyzeClasses(packages = ArchitectureSlices.ROOT_PACKAGE, importOptions = ImportOption.OnlyIncludeTests.class,
	// Release the imported classes after this class; the default keeps them for the whole test JVM.
	cacheMode = CacheMode.PER_CLASS)
class TestCodingRulesTest {

	@ArchTest
	static final ArchRule assertionsUseAssertJ = noClasses()
		.should().dependOnClassesThat().haveFullyQualifiedName("org.junit.jupiter.api.Assertions")
		.because("tests assert with AssertJ");
}
