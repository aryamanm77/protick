package com.yourorg.qbank.core;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class CoreArchitectureTest {

    private static final JavaClasses CORE_MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.yourorg.qbank.core");

    @Test
    void qbankCoreMustRemainPureJavaWithoutFrameworkOrIoDependencies() {
        noClasses()
                .that().resideInAPackage("com.yourorg.qbank.core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.servlet..",
                        "javax.servlet..",
                        "java.sql..",
                        "javax.sql..",
                        "java.io..",
                        "java.nio.file..",
                        "org.apache.poi..",
                        "com.fasterxml.jackson..",
                        "org.flywaydb.."
                )
                .because("qbank-core must remain 100% portable pure Java 17 behind clean port interfaces")
                .check(CORE_MAIN_CLASSES);
    }

    @Test
    void domainModelsMustContainZeroFrameworkAnnotations() {
        noClasses()
                .that().resideInAPackage("com.yourorg.qbank.core.model..")
                .should().beAnnotatedWith("org.springframework.stereotype.Component")
                .orShould().beAnnotatedWith("com.fasterxml.jackson.annotation.JsonProperty")
                .check(CORE_MAIN_CLASSES);
    }
}
