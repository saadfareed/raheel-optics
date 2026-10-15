import java.net.URI
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
val cloudDefaults = Properties().apply { file("cloud.properties").inputStream().use { load(it) } }
val branding = Properties().apply { rootProject.file("branding/brand.properties").inputStream().use { load(it) } }
val dotenv = mutableMapOf<String, String>()
val compactApks = providers.gradleProperty("compactApks").map { it.toBoolean() }.getOrElse(false)
val splitApks = providers.gradleProperty("splitApks").map { it.toBoolean() }.getOrElse(false)
// Keep shrunk APK and bundle intermediates separate: AGP cannot bundle multiple APK outputs.
if (compactApks) layout.buildDirectory.set(layout.projectDirectory.dir("build/compact-apks"))
else if (splitApks) layout.buildDirectory.set(layout.projectDirectory.dir("build/abi-apks"))
rootProject.file(".env").takeIf { it.isFile }?.readLines()?.forEach { line ->
    val text = line.trim()
    if(text.isNotEmpty() && !text.startsWith("#") && text.contains("=")) {
        val (key,raw) = text.split("=",limit=2)
        dotenv[key.trim()] = raw.trim().removeSurrounding("\"").removeSurrounding("'")
    }
}
android {
    namespace = "com.daymark.todo"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    splits {
        abi {
            isEnable = splitApks
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
    defaultConfig {
        applicationId = "pk.raheeloptics.orders"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "1.5.1"
        fun setting(name: String) = providers.gradleProperty(name).orElse(providers.environmentVariable(name)).getOrElse(dotenv[name] ?: cloudDefaults.getProperty(name, ""))
        buildConfigField("String", "SUPABASE_URL", "\"${setting("SUPABASE_URL").replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "SENTRY_DSN", "\"${setting("SENTRY_DSN").replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_PUBLIC_KEY", "\"${setting("SUPABASE_PUBLIC_KEY").replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"${setting("RELEASE_SUPPORT_EMAIL").replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${setting("RELEASE_PRIVACY_URL").replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        resValue("string", "app_name", branding.getProperty("APP_NAME", "Daymark"))
        resValue("string", "brand_tagline", branding.getProperty("BRAND_TAGLINE", "A little focus. A better day."))
        listOf("FOREGROUND_SYNC_SECONDS" to 120..900, "BACKGROUND_SYNC_MINUTES" to 15..1440, "UPDATE_CHECK_SECONDS" to 60..86400,
            "HISTORY_CACHE_DAYS" to 1..90, "HISTORY_CACHE_ENTRIES" to 25..1000,
            "HISTORY_CACHE_BYTES" to 65536..4194304).forEach { (name, bounds) ->
            val value=branding.getProperty(name).toInt()
            require(value in bounds) { "$name must be in $bounds" }
            buildConfigField("int", name, value.toString())
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").res.directories.add(rootProject.file("branding/android-res").absolutePath)
    androidResources { localeFilters.add("en") }
    packaging { jniLibs.useLegacyPackaging = compactApks }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.powersync:core:1.15.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("io.sentry:sentry-android:8.61.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val releaseSetting: (String) -> String = { name ->
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).getOrElse(dotenv[name] ?: cloudDefaults.getProperty(name, ""))
}
val signingPath = releaseSetting("RELEASE_STORE_FILE")
if (signingPath.isNotBlank()) {
    android.signingConfigs.create("production") {
        storeFile = rootProject.file(signingPath)
        storePassword = releaseSetting("RELEASE_STORE_PASSWORD")
        keyAlias = releaseSetting("RELEASE_KEY_ALIAS")
        keyPassword = releaseSetting("RELEASE_KEY_PASSWORD")
    }
    android.buildTypes.getByName("release").signingConfig = android.signingConfigs.getByName("production")
}
android.buildTypes.getByName("release") {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(android.getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
}
val validateReleaseConfiguration = tasks.register("validateReleaseConfiguration") {
    doLast {
        fun https(value: String) = runCatching {
            URI(value).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.query == null && it.fragment == null }
        }.getOrDefault(false)
        val url = releaseSetting("SUPABASE_URL")
        val publicKey = releaseSetting("SUPABASE_PUBLIC_KEY")
        check(https(url)) { "Set the Supabase HTTPS endpoint before releasing." }
        val claims = if(publicKey.count { it == '.' } == 2) runCatching {
            String(Base64.getUrlDecoder().decode(publicKey.split('.')[1]))
        }.getOrDefault("") else ""
        check(publicKey.startsWith("sb_publishable_") || Regex("\\\"role\\\"\\s*:\\s*\\\"anon\\\"").containsMatchIn(claims)) {
            "Set a public Supabase publishable/anon key before releasing."
        }
        check(!publicKey.startsWith("sb_secret_") && !claims.contains("service_role")) { "Secret keys cannot be shipped in the app." }
        check(signingPath.isNotBlank() && rootProject.file(signingPath).isFile &&
            listOf("RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD").all { releaseSetting(it).isNotBlank() }) {
            "Configure the release keystore and signing settings before releasing."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseConfiguration) }
