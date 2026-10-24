package org.example;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.example");

    @Test void businessLayerDoesNotUseWebFrameworkOrDatabaseLibraries() {
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "org.springframework.http..", "jakarta.servlet..",
                        "org.springframework.data..", "org.springframework.jdbc..", "org.springframework.orm..",
                        "jakarta.persistence..", "org.hibernate..", "java.sql..")
                .because("tầng nghiệp vụ không import framework web hay thư viện DB (docs/2026.md)")
                .check(CLASSES);
    }

    @Test void businessLayerDoesNotCallJpaSpecificRepositoryMethods() {
        noClasses().that().resideInAPackage("..service..")
                .should().callMethodWhere(describe("phương thức khai báo trong JpaRepository (flush, saveAndFlush...)",
                        call -> call.getTarget().resolveMember()
                                .map(method -> method.getOwner().isEquivalentTo(JpaRepository.class)).orElse(false)))
                .because("flush/batch là khái niệm của ORM; gói chúng trong repository (vd CartRepository.saveChanges)")
                .check(CLASSES);
    }

    @Test void controllersGoThroughServices() {
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .because("API → Nghiệp vụ → Truy cập dữ liệu; controller không gọi thẳng repository")
                .check(CLASSES);
    }

    @Test void foodCartAndOrderOnlyTalkThroughEachOthersServices() {
        for (String module : new String[]{"food", "cart", "order"}) {
            String root = "org.example." + module;
            noClasses().that().resideOutsideOfPackage(root + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            root + ".entity..", root + ".repository..", root + ".controller..")
                    .because("module khác chỉ được gọi service/dto của module " + module)
                    .check(CLASSES);
        }
    }

    @Test void servicesDoNotDependOnControllers() {
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("..controller..")
                .check(CLASSES);
    }
}
