import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.apollo)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.chencohensolution.journal.sharedLogic"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.apollo.runtime)
            implementation(libs.apollo.engine.ktor)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.core.ktx)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp)
            implementation(libs.okhttp.tls)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

apollo {
    service("journal") {
        // Schema copied from journal-server-go/graph/schema/schema.graphql; operations live next to it.
        srcDir("src/commonMain/graphql")
        packageName.set("com.chencohensolution.journal.graphql")
        // Keep generated models out of the public API (and the iOS framework header).
        generateAsInternal.set(true)
    }
}

// The server repo owns the GraphQL schema (gqlgen is schema-first). Copy it here and commit the result:
//   ./gradlew :sharedLogic:syncServerSchema
// Override the server checkout location with -PjournalServerDir=<path> (default: sibling journal-server-go).
val serverSchemaDir: File = rootProject.file(
    (findProperty("journalServerDir") as String? ?: "../journal-server-go") + "/graph/schema"
)

// Separate task because Copy skips its own actions (NO-SOURCE) when the source folder is missing.
val checkServerSchemaDir = tasks.register("checkServerSchemaDir") {
    val schemaDir = serverSchemaDir
    doLast {
        if (!schemaDir.isDirectory) {
            throw GradleException("Server schema not found at $schemaDir. Pass -PjournalServerDir=<path to journal-server-go>.")
        }
    }
}

tasks.register<Copy>("syncServerSchema") {
    group = "apollo"
    description = "Copies the GraphQL schema from journal-server-go into src/commonMain/graphql."
    dependsOn(checkServerSchemaDir)

    from(serverSchemaDir) {
        include("*.graphql")
        // Apollo only treats *.graphqls as schema; *.graphql files are operations.
        rename { it.removeSuffix(".graphql") + ".graphqls" }
    }
    into(layout.projectDirectory.dir("src/commonMain/graphql"))
}
