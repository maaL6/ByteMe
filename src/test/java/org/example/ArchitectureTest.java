package org.example;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test void pureContractsMustNotDependOnFrameworkOrDatabase() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.example");
        noClasses().that().resideInAnyPackage("..auth.domain..", "..auth.service..", "..order.domain..", "..order.service..", "..shared.api..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..",
                        "javax.servlet..", "java.sql..", "javax.sql..", "org.hibernate..",
                        "..web..", "..persistence..", "..integration..", "..platform..", "..dev..")
                .check(classes);
    }
}
