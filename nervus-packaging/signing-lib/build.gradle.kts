plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.serialization.json)
    implementation(libs.zstd.jni)
    implementation(libs.commons.compress)

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(libs.coroutines.test)
}
