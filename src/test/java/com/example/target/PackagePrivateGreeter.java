package com.example.target;

/**
 * A package-private interface. The JDK defines a dynamic proxy for it in this package, as
 * {@code com.example.target.$Proxy<n>}, rather than in {@code jdk.proxyN}, so the proxy class
 * falls inside the fixture include prefix.
 */
interface PackagePrivateGreeter {
    String greet();
}
