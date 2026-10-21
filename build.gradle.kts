plugins {
    kotlin("jvm") version "2.4.20"
    antlr
    application
}

group = "ru.itmo.applang"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    antlr("org.antlr:antlr4:4.13.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("ru.itmo.applang.cli.MainKt")
}

tasks.generateGrammarSource {
    // ANTLR генерирует visitor-классы (AppLangBaseVisitor и т.п.), listener не нужен.
    // Директория вывода мирроит src/main/antlr/ru/itmo/applang/parser -> .../ru/itmo/applang/parser
    // автоматически, поэтому пакет для сгенерированных классов совпадает с путём грамматики.
    arguments = arguments + listOf("-visitor", "-no-listener")
}

tasks.named("compileKotlin") {
    dependsOn("generateGrammarSource")
}
tasks.named("compileTestKotlin") {
    dependsOn("generateTestGrammarSource")
}

sourceSets {
    main {
        kotlin {
            srcDir(layout.buildDirectory.dir("generated-src/antlr/main"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Набор .albc-файлов совместимости для команды GVM: корректные файлы с ожидаемым выводом и
// испорченные с ожидаемой категорией отказа + manifest.json (src/test/.../compat/ConformanceCorpus.kt).
tasks.register<JavaExec>("conformanceCorpus") {
    group = "verification"
    description = "Собирает набор совместимости .albc и manifest.json в build/conformance"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.itmo.applang.compat.ConformanceCorpusKt")
    workingDir = projectDir
    val outputDir = layout.buildDirectory.dir("conformance")
    args(outputDir.get().asFile.absolutePath)
    outputs.dir(outputDir)
}
