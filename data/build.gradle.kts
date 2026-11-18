plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "tachiyomi.data"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    sqldelight {
        databases {
            create("Database") {
                packageName.set("tachiyomi.data")
                dialect(libs.sqldelight.dialects.sql)
                schemaOutputDirectory.set(project.file("./src/main/sqldelight"))
            }
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

val migrationFixClasspath by configurations.creating

val compileMigrationFix = tasks.register<JavaCompile>("compileMigrationFix") {
    source = fileTree("src/migrationFix/java")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/migrationFix/classes"))
    classpath = migrationFixClasspath
}

val testMigrationFix = tasks.register<JavaExec>("testMigrationFix") {
    dependsOn(compileMigrationFix)
    classpath = files(compileMigrationFix.map { it.destinationDirectory }, migrationFixClasspath)
    mainClass.set("tachiyomi.data.migration.SqliteDriverInitializerTest")
}

val migrationFixJar = tasks.register<Jar>("migrationFixJar") {
    dependsOn(compileMigrationFix)
    from(compileMigrationFix.map { it.destinationDirectory }) {
        exclude("**/*Test*")
    }
    from("src/migrationFix/resources")
    archiveBaseName.set("sqlite-driver-fix")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/migrationFix/jar"))
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.domain)
    implementation(projects.core.common)

    api(libs.bundles.sqldelight)

    migrationFixClasspath("app.cash.sqldelight:gradle-plugin:${libs.versions.sqldelight.get()}")

    "DatabaseMigrationEnv"(files(migrationFixJar))
}
