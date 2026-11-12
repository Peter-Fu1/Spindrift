plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "io.github.peterfu.hoyoreminder"
 compileSdk = 35
 buildFeatures { buildConfig = true }
 defaultConfig { manifestPlaceholders["appLabel"] = "Spindrift"; applicationId = "io.github.peterfu.hoyoreminder"; minSdk = 28; targetSdk = 35; versionCode = 20; versionName = "1.0.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 val revision=providers.environmentVariable("GITHUB_SHA").orNull?.takeIf{Regex("[a-f0-9]{40}").matches(it)}?:"local"
 defaultConfig { buildConfigField("String","SOURCE_REVISION","\"$revision\""); buildConfigField("boolean","DETAILED_DIAGNOSTICS","false") }
 buildTypes {
  create("preview") { initWith(getByName("release")); buildConfigField("boolean","DETAILED_DIAGNOSTICS","true"); isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro"); applicationIdSuffix = ".preview"; manifestPlaceholders["appLabel"] = "Spindrift"; matchingFallbacks += listOf("release") }
  // Unsigned full upgrade material for the audited local 0.1.12 .debug candidate; no replacement key is assigned.
  create("legacyUpgrade") { initWith(getByName("release")); applicationIdSuffix = ".debug"; signingConfig = null; isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro"); matchingFallbacks += listOf("release") }
  getByName("debug") { applicationIdSuffix = ".debug" }
  getByName("release") { manifestPlaceholders["appLabel"] = "Spindrift"; isDebuggable = false; isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro") }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webAssets"))
 testOptions { unitTests.isReturnDefaultValues = true }
}
val webAssets by tasks.registering(Sync::class) {
 from(rootProject.file("web")) { include("index.html","app.js","platform-adapter.js","styles.css","resource-estimate.js","ui-model.js","checkin-ui.js","sms-ui.js","normalization.mjs","refresh-contract.mjs","shared/platform-contract.mjs") }
 into(layout.buildDirectory.dir("generated/webAssets/web"))
}
tasks.named("preBuild") { dependsOn(webAssets) }
dependencies {
 implementation("androidx.work:work-runtime-ktx:2.10.1")
 implementation("androidx.activity:activity-ktx:1.10.1")
 implementation("androidx.webkit:webkit:1.13.0")
 implementation("com.google.zxing:core:3.5.3")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
}

// Explicit CI-only upgrade laboratory. Never used by normal release/preview builds.
val mainUpgradeLab = providers.gradleProperty("mainUpgradeTest").orNull == "true"
if (mainUpgradeLab) {
 require(System.getenv("CI") == "true") { "Upgrade lab is only available on isolated CI runners" }
 android {
  defaultConfig { versionCode = providers.gradleProperty("mainTestVersionCode").get().toInt(); versionName = "0.0.0-upgrade-lab-code"+versionCode }
  signingConfigs {
   create("mainUpgradeLab") {
    storeFile = file(System.getenv("HOYO_UPGRADE_TEST_KEY") ?: error("Missing isolated test key"))
    storePassword = System.getenv("HOYO_UPGRADE_TEST_PASSWORD") ?: error("Missing isolated test password")
    keyAlias = "upgrade-lab"; keyPassword = storePassword
    enableV1Signing = false; enableV2Signing = true; enableV3Signing = true
   }
  }
  buildTypes.getByName("release") {
   signingConfig = signingConfigs.getByName("mainUpgradeLab")
   testProguardFiles("main-upgrade-test-r8.pro")
   // The runner shares provided Kotlin/AndroidX facades with its target; shrinking the lab
   // removes facade classes before the runner starts. Test OS upgrade/data semantics here;
   // normal production release remains minified and is separately cold-launch/asset verified.
   isMinifyEnabled = false; isShrinkResources = false
  }
  testBuildType = "release"
 }
}

if (mainUpgradeLab) { dependencies { androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0") } }
