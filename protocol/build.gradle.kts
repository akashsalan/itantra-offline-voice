plugins {
    id("java-library")
    id("com.google.protobuf")
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies { api("com.google.protobuf:protobuf-javalite:4.36.1") }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.1" }
    generateProtoTasks { all().configureEach { builtins { named("java") { option("lite") } } } }
}
dependencyLocking { lockAllConfigurations() }
