import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        explicitApi()
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("failed")
        showStandardStreams = providers.gradleProperty("showOutput").isPresent
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Dictionary evaluation (spec 05c, docs/EVALUATION.md): T0 lint, T1 fast tier, T2 full tier. The evaluation code lives in
// the test source set (it uses the engine's test-only diagnostics); its inputs are evaluation/ and the evaluated
// dictionary files, its reports go to engine/build/evaluation/<tier>/ unless -PevalOut=… is given.
fun registerEvaluation(name: String, tier: String, description: String) = tasks.register<JavaExec>(name) {
    group = "verification"
    this.description = description
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.metrokeys.engine.eval.EvaluationMain")
    maxHeapSize = "3g"
    workingDir = rootProject.projectDir
    if (providers.gradleProperty("evalDump").isPresent) systemProperty("eval.dump", "true")
    val out = providers.gradleProperty("evalOut").orElse("")
    val dict = providers.gradleProperty("evalDict").orElse("")
    val baseline = providers.gradleProperty("evalWriteBaseline").orElse("")
    val baselineName = providers.gradleProperty("evalBaselineName").orElse("")
    val languages = providers.gradleProperty("evalLanguages").orElse("")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--root=${rootProject.projectDir}", "--tier=$tier", "--out=${out.get()}", "--dict=${dict.get()}",
            "--writeBaseline=${baseline.get()}", "--baselineName=${baselineName.get()}", "--languages=${languages.get()}")
    })
}

registerEvaluation("evalLint", "lint", "Dictionary lint L1–L5 of every language (spec 05c §4, tier T0)")
registerEvaluation("evalFast", "fast", "Fast dictionary evaluation: dev half, B1+B2, variant N, single profile (05c tier T1)")
registerEvaluation("evalFull", "full", "Full dictionary evaluation: both halves, N and S, both profiles, freshness run (05c tier T2)")
