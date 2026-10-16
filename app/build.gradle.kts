import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional release signing: keystore.properties at the repository root (git-ignored) with storeFile,
// storePassword, keyAlias and keyPassword. Without it (e.g. on CI) the release build stays unsigned.
val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "app.metrokeys"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.metrokeys"
        minSdk = 26
        targetSdk = 37
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = libs.versions.appVersionName.get()
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    sourceSets {
        getByName("main") {
            // The layout, metrics and theme data of the specification are the normative source (spec 01 §12.3).
            assets.directories.add("../spec/data")
        }
    }

    // The keyboard switches its own texts to the app language at run time (spec 07 §9.5).
    bundle {
        language {
            enableSplit = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.customview)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    testImplementation(libs.junit4)
}

// ---------------------------------------------------------------- licenses page (spec 07 §9.14)

/**
 * Generates `assets/licenses.txt` for the licenses page from docs/DATA-SOURCES.md, the bundled licence files and
 * the resolved runtime libraries. Every library group must have a known licence, so a new dependency cannot slip in
 * without its licence text.
 */
abstract class LicensesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataSources: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val selawik: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dictionaries: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val unicode: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apache: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val gpl: RegularFileProperty

    /** Licences of spelling dictionaries the word lists are built from (05b §2.5, 0.7.0): GNU LGPL 3, MPL 2.0. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lgpl: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mpl: RegularFileProperty

    @get:Input
    abstract val libraries: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** A row "| Key | Value |" of the table below [section] in DATA-SOURCES.md, without Markdown markup. */
    private fun row(md: List<String>, section: String, key: String): String {
        val start = md.indexOfFirst { it.trim() == section }
        require(start >= 0) { "no section $section in DATA-SOURCES.md" }
        val line = md.drop(start + 1).takeWhile { !it.startsWith("## ") }.first { it.startsWith("| $key |") }
        return line.split("|")[2].trim().replace("`", "").replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
    }

    /** Joins hard-wrapped lines of a paragraph; indented, list and separator lines keep their break. */
    private fun reflow(text: String): String = text.replace("\r", "").split(Regex("\\n\\s*\\n")).joinToString("\n\n") { para ->
        val lines = para.lines().filter { it.isNotBlank() }
        val indent = lines.minOfOrNull { l -> l.length - l.trimStart().length } ?: 0
        val out = StringBuilder()
        for ((i, raw) in lines.withIndex()) {
            val l = raw.drop(indent).trimEnd()
            val keepBreak = l.startsWith(" ") || l.startsWith("-") || l.startsWith("*") || l.startsWith("•") ||
                Regex("^\\(?[0-9a-z]{1,2}[.)] ").containsMatchIn(l) || (i > 0 && heading(lines[i - 1]))
            if (i > 0) out.append(if (keepBreak || l.all { it == '-' || it == '=' }) "\n" else " ")
            out.append(if (i > 0 && !keepBreak) l.trimStart() else l)
        }
        out.toString()
    }

    /** Separator lines and short upper-case headings end with a line break. */
    private fun heading(line: String): Boolean {
        val t = line.trim()
        return t.all { it == '-' || it == '=' } || (t.length < 60 && t.any { it.isLetter() } && t.none { it.isLowerCase() })
    }

    @TaskAction
    fun generate() {
        val md = dataSources.get().asFile.readLines()
        val libs = libraries.get()
        val apacheGroups = listOf(
            "androidx.", "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains:", "com.google.guava", "org.jspecify",
        )
        val unknown = libs.filter { l -> apacheGroups.none { l.startsWith(it) } }
        require(unknown.isEmpty()) { "no licence known for $unknown; extend LicensesTask" }
        val text = buildString {
            append("METROKEYS\n\n")
            append("Copyright © 2026 Dennis Braun.\n\n")
            append("MetroKeys is free software: you can redistribute it and/or modify it under the terms of the GNU General ")
            append("Public License, version 3 only, as published by the Free Software Foundation. It is distributed in the hope ")
            append("that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or ")
            append("FITNESS FOR A PARTICULAR PURPOSE. Source code: https://github.com/itsDNNS/MetroKeys\n\n")
            append("Windows Phone is a trademark of Microsoft Corporation. MetroKeys is not affiliated with Microsoft.\n\n")
            // the GPL indents first lines and centres its title; the licences page shows plain paragraphs
            append(reflow(gpl.get().asFile.readText()).lines().joinToString("\n") { it.trim() }.trim()).append("\n\n")
            append("MetroKeys includes the following font, data and libraries. Their licences follow.\n\n")
            append("SELAWIK FONT\n\n")
            append("Selawik, source: ${row(md, "## Selawik font", "Source")}.\n\n")
            append("Licence: ${row(md, "## Selawik font", "Licence").substringBefore(';')}.\n\n")
            append(reflow(selawik.get().asFile.readText()).trim()).append("\n\n")
            append("DICTIONARIES\n\n")
            append("The word lists, word pairs and elision prefixes of the keyboard languages are built from the sources ")
            append("named below; each language's text states their licence. From version 0.7.0 they are distributed under ")
            append("the GNU General Public License, version 3 only, like MetroKeys (its text is shown above), and the ")
            append("CC BY-SA 4.0 material is used under the CC BY-SA 4.0 / GPLv3 compatibility. Their source form is ")
            append("published with each release at https://github.com/itsDNNS/MetroKeys/releases.\n\n")
            for (f in dictionaries.files.sortedBy { it.name }) append(reflow(f.readText()).trim()).append("\n\n")
            append("GNU LESSER GENERAL PUBLIC LICENSE, VERSION 3 (spelling dictionary of the Portuguese word list)\n\n")
            append(reflow(lgpl.get().asFile.readText()).lines().joinToString("\n") { it.trim() }.trim()).append("\n\n")
            append("MOZILLA PUBLIC LICENSE, VERSION 2.0 (spelling dictionary of the French word list)\n\n")
            append(reflow(mpl.get().asFile.readText()).trim()).append("\n\n")
            append("CREATIVE COMMONS ATTRIBUTION-SHAREALIKE 4.0 INTERNATIONAL (CC BY-SA 4.0): ")
            append("https://creativecommons.org/licenses/by-sa/4.0/legalcode\n\n")
            append("EMOJI DATA\n\n")
            append("Emoji list, source: ${row(md, "## Emoji page data", "Source")}.\n\n")
            append("Licence: ${row(md, "## Emoji page data", "Licence").substringBefore(" (")}.\n\n")
            append(reflow(unicode.get().asFile.readText()).trim()).append("\n\n")
            append("LIBRARIES\n\n")
            append("The following libraries are used under the Apache License, Version 2.0:\n\n")
            append(libs.joinToString("\n")).append("\n\n")
            append(reflow(apache.get().asFile.readText()).trim()).append("\n")
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        File(out, "licenses.txt").writeText(text)
    }
}

androidComponents {
    onVariants { variant ->
        val classpath = configurations.named("${variant.name}RuntimeClasspath")
        val task = tasks.register<LicensesTask>("generate${variant.name.replaceFirstChar { it.uppercase() }}Licenses") {
            dataSources.set(rootProject.file("docs/DATA-SOURCES.md"))
            selawik.set(file("src/main/assets/licenses/Selawik-OFL.txt"))
            dictionaries.from(rootProject.fileTree("engine/src/main/resources/app/metrokeys/engine/dict") { include("LICENSE-*.txt") })
            unicode.set(file("src/main/assets/licenses/Unicode-LICENSE.txt"))
            apache.set(file("licenses/Apache-2.0.txt"))
            gpl.set(file("licenses/GPL-3.0.txt"))
            lgpl.set(file("licenses/LGPL-3.0.txt"))
            mpl.set(file("licenses/MPL-2.0.txt"))
            libraries.set(
                classpath.map { c ->
                    c.incoming.resolutionResult.allComponents
                        .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                        .filter { !it.module.endsWith("-bom") }
                        .map { "${it.group}:${it.module} ${it.version}" }
                        .distinct().sorted()
                },
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, LicensesTask::outputDir)
    }
}
