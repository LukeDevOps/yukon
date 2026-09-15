package com.example.remapped;

/**
 * Stands in for the relocated name a shading step would give
 * {@link com.example.remap.Before}. Its only purpose is to exist on the test classpath so
 * {@link io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder}'s remapped
 * {@link net.bytebuddy.pool.TypePool} can resolve the rewritten reference.
 */
public class Before {
}
