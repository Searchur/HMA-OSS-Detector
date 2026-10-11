import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.BuiltArtifact
import com.android.build.api.variant.VariantOutputConfiguration
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

private val appDisplayName = "HMA OSS Detector"
private val externalVersion = "1.2"
private val internalVersion = 17
private val buildTime = LocalDateTime.now(ZoneOffset.UTC)
    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
private val unsignedRelease = providers.gradleProperty("unsignedRelease")
    .map { it.toBooleanStrict() }.getOrElse(false)
private val keystorePropertiesFile = rootProject.file("keystore.properties")
private val keystoreProperties = Properties().apply {
    if (!unsignedRelease && keystorePropertiesFile.isFile) {
        keystorePropertiesFile.reader(Charsets.UTF_8).use { load(it) }
    }
}

private fun signingProperty(name: String): String =
    requireNotNull(keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }) {
        "Missing $name in keystore.properties."
    }

android {
    namespace = "com.hmaossdetector"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        applicationId = "com.hmaossdetector"
        minSdk = 31
        targetSdk = 37
        versionCode = internalVersion
        versionName = "$buildTime-$externalVersion($internalVersion)"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!unsignedRelease && keystorePropertiesFile.isFile) {
            create("localRelease") {
                storeFile = rootProject.file(signingProperty("storeFile"))
                storePassword = signingProperty("storePassword")
                keyAlias = signingProperty("keyAlias")
                keyPassword = signingProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = when {
                unsignedRelease -> null
                keystorePropertiesFile.isFile -> signingConfigs.getByName("localRelease")
                else -> signingConfigs.getByName("debug")
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    val taskName = "rename${variant.name.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }}Apk"
    val renameTask = tasks.register(taskName, RenameApkTask::class.java)
    val request = variant.artifacts
        .use(renameTask)
        .wiredWithDirectories(RenameApkTask::inputApkFolder, RenameApkTask::outputApkFolder)
        .toTransformMany(SingleArtifact.APK)
    renameTask.configure {
        transformationRequest.set(request)
        artifactBaseName.set(buildArtifactBaseName())
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

private fun buildArtifactBaseName(): String =
    "$appDisplayName-$buildTime-$externalVersion($internalVersion)"

abstract class RenameApkTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputApkFolder: DirectoryProperty

    @get:OutputDirectory
    abstract val outputApkFolder: DirectoryProperty

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<RenameApkTask>>

    @get:Input
    abstract val artifactBaseName: Property<String>

    @TaskAction
    fun renameArtifacts() {
        transformationRequest.get().submit(this) { builtArtifact ->
            val inputFile = File(builtArtifact.outputFile)
            val outputFile = outputApkFolder.file(apkFileName(builtArtifact)).get().asFile
            outputFile.parentFile.mkdirs()
            inputFile.copyTo(outputFile, overwrite = true)
            outputFile
        }
    }

    private fun apkFileName(builtArtifact: BuiltArtifact): String {
        val baseName = artifactBaseName.get()
        return when (builtArtifact.outputType) {
            VariantOutputConfiguration.OutputType.ONE_OF_MANY -> {
                val filterSuffix = builtArtifact.filters.joinToString("-") { filter ->
                    "${filter.filterType.name.lowercase()}-${filter.identifier}"
                }
                "$baseName-$filterSuffix.apk"
            }

            VariantOutputConfiguration.OutputType.SINGLE,
            VariantOutputConfiguration.OutputType.UNIVERSAL -> "$baseName.apk"
        }
    }
}
