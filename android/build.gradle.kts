// Top-level build file — keeps versions in one place.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Generates serializers for @Serializable data classes — without it the
    // runtime can't find them and Ktor's ContentNegotiation throws 500.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
