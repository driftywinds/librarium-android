// Top-level build file. Plugin versions come from the version catalog so the
// app module never hard-codes a version string.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
