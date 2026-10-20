package com.openvie.api.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ModularMonolithArchitectureTest {
    private static final Set<String> BUSINESS_MODULES = Set.of(
            "ai", "auth", "chat", "document", "notification", "tenant", "workflow");

    private static final ImportOption MAIN_OUTPUT_ONLY = location -> {
        String path = location.asURI().getPath();
        return path == null || !path.contains("/test-classes/");
    };

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(MAIN_OUTPUT_ONLY)
            .importPackages("com.openvie.api");
    private final JavaClasses businessClasses = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(MAIN_OUTPUT_ONLY)
            .importPackages(
            BUSINESS_MODULES.stream().map(module -> "com.openvie.api." + module)
                    .toArray(String[]::new));

    @Test
    void businessModulesUseOnlyPublishedBoundaries() {
        for (String source : BUSINESS_MODULES) {
            for (String target : BUSINESS_MODULES) {
                if (source.equals(target)) {
                    continue;
                }
                DescribedPredicate<JavaClass> targetInternals =
                        JavaClass.Predicates.resideInAPackage(
                                        "com.openvie.api." + target + "..")
                                .and(JavaClass.Predicates.resideOutsideOfPackage(
                                        "com.openvie.api." + target + ".api.."));
                ArchRule rule = noClasses()
                        .that().resideInAPackage("com.openvie.api." + source + "..")
                        .should().dependOnClassesThat(targetInternals);
                rule.check(classes);
            }
        }
    }

    @Test
    void apiContractsDoNotLeakInternals() {
        noClasses().that().resideInAPackage("com.openvie.api.*.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.openvie.api.*.model..",
                        "com.openvie.api.*.repository..",
                        "com.openvie.api.*.service..",
                        "com.openvie.api.*.query..",
                        "com.openvie.api.*.dto..")
                .check(classes);
    }

    @Test
    void commonDoesNotDependOnBusinessModules() {
        noClasses().that().resideInAPackage("com.openvie.api.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BUSINESS_MODULES.stream()
                                .map(module -> "com.openvie.api." + module + "..")
                                .toArray(String[]::new))
                .check(classes);
    }

    @Test
    void businessModuleGraphIsAcyclic() {
        slices().matching("com.openvie.api.(*)..")
                .should().beFreeOfCycles()
                .check(businessClasses);
    }

    @Test
    void moduleApisAreInterfaces() {
        classes().that().haveSimpleNameEndingWith("Api")
                .and().resideInAPackage("com.openvie.api.*.api..")
                .should().beInterfaces()
                .check(classes);
    }

    @Test
    void jdbcAccessLivesInRepositoryOrQueryPackages() {
        noClasses().that().resideInAPackage("com.openvie.api.*..")
                .and().resideOutsideOfPackages(
                        "com.openvie.api.*.repository..",
                        "com.openvie.api.*.query..",
                        "com.openvie.api.common..",
                        "com.openvie.api.bootstrap..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.jdbc..", "jakarta.persistence.EntityManager")
                .check(classes);
    }
}
