package kr.kro.airbob.architecture;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;

/**
 * Classifies application classes into domain slices for the dependency rules.
 * A domain keeps one name across the legacy and template layouts:
 * {@code domain.<x>}, {@code core.domain.<x>}, and {@code core.api.controller.v1.<x>} all belong to {@code <x>}.
 * {@code search} and {@code messaging} are slices as well.
 */
final class ArchitectureSlices {

	static final String ROOT_PACKAGE = "kr.kro.airbob";

	private static final Pattern DOMAIN_PACKAGE = Pattern.compile(
		"^kr\\.kro\\.airbob\\.(?:domain|core\\.domain|core\\.api\\.controller\\.v1)\\.([a-z0-9]+)(?:\\..*)?$");
	private static final Pattern TOP_LEVEL_PACKAGE = Pattern.compile(
		"^kr\\.kro\\.airbob\\.(core\\.[a-z0-9]+|[a-z0-9]+)(?:\\..*)?$");
	private static final Pattern REPOSITORY_PACKAGE = Pattern.compile("^.*\\.repository(?:\\..*)?$");

	private ArchitectureSlices() {
	}

	/** The domain slice of a class, or empty for shared packages (common, config, geo, core.support, ...). */
	static Optional<String> domainOf(JavaClass javaClass) {
		String packageName = javaClass.getPackageName();
		Matcher domain = DOMAIN_PACKAGE.matcher(packageName);
		if (domain.matches()) {
			return Optional.of(domain.group(1));
		}
		if (packageName.equals(ROOT_PACKAGE + ".search") || packageName.startsWith(ROOT_PACKAGE + ".search.")) {
			return Optional.of("search");
		}
		if (packageName.equals(ROOT_PACKAGE + ".messaging") || packageName.startsWith(ROOT_PACKAGE + ".messaging.")) {
			return Optional.of("messaging");
		}
		return Optional.empty();
	}

	/** The domain slice of a class, or its top-level package (for example common, config, core.support). */
	static String ownerOf(JavaClass javaClass) {
		return domainOf(javaClass).orElseGet(() -> {
			Matcher topLevel = TOP_LEVEL_PACKAGE.matcher(javaClass.getPackageName());
			return topLevel.matches() ? topLevel.group(1) : javaClass.getPackageName();
		});
	}

	static boolean isApplicationClass(JavaClass javaClass) {
		return javaClass.getPackageName().startsWith(ROOT_PACKAGE);
	}

	/**
	 * Spring Data or hand-written data access types: repository packages, *Repository types, and types that
	 * directly extend a Spring Data interface. Library classes are not imported (see archunit.properties), so
	 * only direct supertypes are inspected, by name.
	 */
	static boolean isRepository(JavaClass javaClass) {
		return isApplicationClass(javaClass)
			&& (REPOSITORY_PACKAGE.matcher(javaClass.getPackageName()).matches()
			|| javaClass.getSimpleName().endsWith("Repository")
			|| javaClass.getRawInterfaces().stream()
				.anyMatch(type -> type.getName().startsWith("org.springframework.data.")));
	}

	/** QueryDSL Q-types mirror entity associations; they are skipped as dependency origins. */
	static boolean isGeneratedQueryType(JavaClass javaClass) {
		return javaClass.getSimpleName().matches("Q[A-Z].*")
			&& javaClass.getRawSuperclass()
				.map(superclass -> superclass.getName().startsWith("com.querydsl."))
				.orElse(false);
	}

	static DescribedPredicate<JavaClass> repositories() {
		return DescribedPredicate.describe("repositories", ArchitectureSlices::isRepository);
	}
}
