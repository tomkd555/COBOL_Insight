plugins {
    `kotlin-dsl`
}

// build-logic は独立したビルドであり、ルート側のリポジトリ指定は及ばない。
// kotlin-dsl が使う Kotlin の依存とGradleプラグインの取得先をここで指定する。
repositories {
    mavenCentral()
    gradlePluginPortal()
}
