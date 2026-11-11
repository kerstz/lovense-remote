// Gradle init script used by CI (not by normal builds): enables dependency
// locking so that `--write-locks` writes app/gradle.lockfile, which the OSV
// scanner reads to check every resolved artifact against known CVEs.
//
//   ./gradlew -I tools/lockfile.init.gradle.kts :app:dependencies --write-locks \
//       --configuration releaseRuntimeClasspath
allprojects {
    dependencyLocking { lockAllConfigurations() }
}
