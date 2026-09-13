// The one class in here is appended to the target JVM's bootstrap classloader at premain, so
// that every instrumented class, whatever classloader defines it, can reach it from its own
// <clinit>. Bootstrap classes cannot see anything outside the bootstrap loader, so this module
// must stay Java-only with no dependencies at all: a Kotlin import or a library reference here
// would compile fine and fail at runtime inside a customer JVM with NoClassDefFoundError. Keeping
// it as its own module makes the build enforce that rather than a reviewer.
plugins {
    java
}

group = "io.github.lukedevops"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.jar {
    archiveBaseName.set("yukon-bootstrap")
}
