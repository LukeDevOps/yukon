package io.github.lukedevops.yukon.bootstrap;

import java.lang.System.Logger.Level;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands each instrumented class its probe-count array at class-initialisation time.
 *
 * <p>Lives in the bootstrap classloader, so a class defined by any loader at all, including an
 * isolated one that never delegates to the system loader, can call {@link #resolve} from its
 * woven {@code <clinit>} without a {@code NoClassDefFoundError}. The agent itself lives in the
 * system loader and cannot be referenced from here, so it plugs its registry in through
 * {@link #install(Resolver)} at premain.
 *
 * <p>A miss (no resolver installed yet, or the registry has no array for this class) returns a
 * fresh array of the requested size and logs once. An uncounted class is the worst case this
 * design allows; a null field or an undersized array would surface as an exception inside the
 * application's own methods, which never is.
 */
public final class YukonProbeArrays {

    /** Implemented by the agent; looks the class's array up in its registry. May return null. */
    public interface Resolver {
        long[] resolve(String className, long layoutHash, int probeCount, ClassLoader classLoader);
    }

    private static volatile Resolver resolver;
    private static final Set<String> MISSED = ConcurrentHashMap.newKeySet();

    private YukonProbeArrays() {
    }

    public static void install(Resolver newResolver) {
        resolver = newResolver;
    }

    /** Called from every instrumented class's {@code <clinit>}; every argument is a constant woven at transform time. */
    public static long[] resolve(String className, long layoutHash, int probeCount, ClassLoader classLoader) {
        Resolver current = resolver;
        if (current != null) {
            long[] counts = current.resolve(className, layoutHash, probeCount, classLoader);
            if (counts != null && counts.length == probeCount) {
                return counts;
            }
        }
        if (MISSED.add(className)) {
            System.getLogger(YukonProbeArrays.class.getName()).log(
                    Level.WARNING,
                    "yukon: no registered probe array for " + className
                            + "; its hits will not be counted (resolver installed: " + (current != null) + ")");
        }
        return new long[probeCount];
    }
}
