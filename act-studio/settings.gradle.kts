rootProject.name = "act-studio"

val rootDir = rootProject.projectDir.parentFile!!
includeBuild(file("$rootDir/nervus-app-sdk"))
includeBuild(file("$rootDir/nervus-packaging"))

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
}
