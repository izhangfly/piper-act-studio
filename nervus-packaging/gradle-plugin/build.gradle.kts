plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

sourceSets {
    create("functionalTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

dependencies {
    implementation(project(":signing-lib"))
    implementation(gradleApi())
    implementation(libs.kotlin.gradle.plugin.api)

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    add("functionalTestImplementation", project(":signing-lib"))
    add("functionalTestImplementation", gradleTestKit())
    add("functionalTestImplementation", "org.junit.jupiter:junit-jupiter-api:5.10.1")
    add("functionalTestRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine:5.10.1")
    add("functionalTestRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.10.1")
}

gradlePlugin {
    plugins {
        register("nspkg") {
            id = "com.nervus.packaging"
            implementationClass = "com.nervus.packaging.gradle.NspkgPlugin"
        }
    }
}

val functionalTest by tasks.registering(Test::class) {
    description = "Runs the functional tests"
    group = "verification"
    testClassesDirs = sourceSets["functionalTest"].output.classesDirs
    classpath = sourceSets["functionalTest"].runtimeClasspath
    useJUnitPlatform()
    mustRunAfter(tasks.test)
}

tasks.check {
    dependsOn(functionalTest)
}