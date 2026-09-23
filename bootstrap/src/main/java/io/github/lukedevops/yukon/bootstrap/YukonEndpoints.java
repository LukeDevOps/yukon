package io.github.lukedevops.yukon.bootstrap;

import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandleInfo;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one seam inlined framework advice calls to record and count HTTP endpoints.
 *
 * <p>Lives in the bootstrap classloader, next to {@link YukonProbeArrays}, so advice woven into
 * Spring's, Ktor's, or the JDK's own classes can reach it from any classloader at all. The agent
 * itself lives on the system loader and cannot be referenced from here, so it plugs its endpoint
 * registry in through {@link #install(Resolver)} at premain.
 *
 * <p>Every method here is safe to call at any time, from any thread, before or after {@link
 * #install}, and never throws. A framework's own request path calls these methods directly;
 * letting an exception escape from here would mean a bug in this agent breaks the application it
 * is only supposed to be observing. Every delegate call is wrapped in a {@code try/catch
 * (Throwable)}: a failure is logged once and the call returns null or does nothing, never more.
 *
 * <p>A framework can register or dispatch to an endpoint before the agent has installed its
 * resolver: a class can initialise during premain, or this seam can be reachable from the
 * bootstrap loader before {@code Agent.start} finishes wiring the registry. {@link #register},
 * {@link #recordDispatch}, and {@link #declare} buffer a small record for that window instead of
 * dropping the call on the floor, and {@link #install} replays the buffer in order once a
 * resolver is in hand. The buffer is bounded, since an adopter who never installs an agent at all
 * (a dependency pulled in by mistake, a misconfigured attach) must not leak memory for the life of
 * the process.
 *
 * <p>The seam also remembers which method each handler lambda calls (ADR 0035). A handler written
 * as a lambda or a method reference reaches a framework as a hidden class, whose name is not
 * stable and joins to nothing. Advice on the JDK's lambda factory calls {@link #recordLambdaClass}
 * as each such class is spun, and the framework advice calls {@link #lambdaImplementation} to get
 * the method back.
 */
public final class YukonEndpoints {

    /** Implemented by the agent; backs every entry point below with the real endpoint registry. */
    public interface Resolver {
        Object lookup(Object key);

        Object register(
                Object key,
                String framework,
                String verb,
                String verbatimTemplate,
                String contextPath,
                String handlerClass,
                String handlerMethod,
                String handlerDescriptor);

        Object recordDispatch(
                Object key, String framework, String verb, String verbatimTemplate, String contextPath, String handlerClass);

        Object recordDispatchIfUnowned(
                Object key, String framework, String verb, String verbatimTemplate, String contextPath, String handlerClass);

        void declare(String module, Object frameworkObject);

        void hit(Object entry);

        void attachHandler(Object entry, String handlerClass, String handlerMethod, String handlerDescriptor);

        void disableModule(String module, String reason);
    }

    /**
     * The method a lambda class calls: its owner's {@link Class#getName()}, its name, and its JVM
     * descriptor. The descriptor is the method's own, so it starts with any values the lambda
     * captured.
     */
    public static final class LambdaImplementation {
        public final String className;
        public final String methodName;
        public final String descriptor;

        LambdaImplementation(String className, String methodName, String descriptor) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
        }
    }

    private enum RecordKind {
        REGISTER,
        DISPATCH,
        DISPATCH_IF_UNOWNED,
        DECLARE,
        FAILURE,
    }

    /** One call this seam could not deliver yet, held until {@link #install} replays it. */
    private static final class BufferedRecord {
        final RecordKind kind;
        final String module;
        final Object key;
        final String verb;
        final String verbatimTemplate;
        final String contextPath;
        final String handlerClass;
        final String handlerMethod;
        final String handlerDescriptor;
        final String reason;
        final Object frameworkObject;

        private BufferedRecord(
                RecordKind kind,
                String module,
                Object key,
                String verb,
                String verbatimTemplate,
                String contextPath,
                String handlerClass,
                String handlerMethod,
                String handlerDescriptor,
                String reason,
                Object frameworkObject) {
            this.kind = kind;
            this.module = module;
            this.key = key;
            this.verb = verb;
            this.verbatimTemplate = verbatimTemplate;
            this.contextPath = contextPath;
            this.handlerClass = handlerClass;
            this.handlerMethod = handlerMethod;
            this.handlerDescriptor = handlerDescriptor;
            this.reason = reason;
            this.frameworkObject = frameworkObject;
        }

        static BufferedRecord forRegister(
                String module,
                Object key,
                String verb,
                String verbatimTemplate,
                String contextPath,
                String handlerClass,
                String handlerMethod,
                String handlerDescriptor) {
            return new BufferedRecord(
                    RecordKind.REGISTER,
                    module,
                    key,
                    verb,
                    verbatimTemplate,
                    contextPath,
                    handlerClass,
                    handlerMethod,
                    handlerDescriptor,
                    null,
                    null);
        }

        static BufferedRecord forDispatch(
                String module, Object key, String verb, String verbatimTemplate, String contextPath, String handlerClass) {
            return new BufferedRecord(
                    RecordKind.DISPATCH, module, key, verb, verbatimTemplate, contextPath, handlerClass, null, null, null, null);
        }

        static BufferedRecord forDispatchIfUnowned(
                String module, Object key, String verb, String verbatimTemplate, String contextPath, String handlerClass) {
            return new BufferedRecord(
                    RecordKind.DISPATCH_IF_UNOWNED,
                    module,
                    key,
                    verb,
                    verbatimTemplate,
                    contextPath,
                    handlerClass,
                    null,
                    null,
                    null,
                    null);
        }

        static BufferedRecord forDeclare(String module, Object frameworkObject) {
            return new BufferedRecord(RecordKind.DECLARE, module, null, null, null, null, null, null, null, null, frameworkObject);
        }

        static BufferedRecord forFailure(String module, String reason) {
            return new BufferedRecord(RecordKind.FAILURE, module, null, null, null, null, null, null, null, reason, null);
        }
    }

    private static final System.Logger LOG = System.getLogger(YukonEndpoints.class.getName());
    private static final int BUFFER_CAPACITY = 4096;

    private static final Object BUFFER_LOCK = new Object();
    private static final Deque<BufferedRecord> BUFFER = new ArrayDeque<>();
    private static boolean bufferOverflowLogged;

    private static volatile Resolver resolver;

    private static final Set<String> DISABLED_MODULES = ConcurrentHashMap.newKeySet();
    private static volatile boolean anyDisabled;
    private static final Set<String> FAILURE_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Set<String> DELEGATE_FAILURE_LOGGED = ConcurrentHashMap.newKeySet();

    private static volatile Set<String> handlerInterfaces = Collections.emptySet();
    private static final Map<Class<?>, LambdaImplementation> LAMBDA_IMPLEMENTATIONS = new WeakHashMap<>();
    private static volatile boolean lambdaFailureLogged;

    private YukonEndpoints() {
    }

    /**
     * Installs the resolver every entry point below delegates to, then replays whatever was
     * buffered before this call in the order it arrived.
     *
     * <p>Replaying happens under the same lock that guards buffering, so a call to {@link
     * #register} or {@link #recordDispatch} racing this one either lands in the buffer and gets
     * replayed here, or finds the resolver already installed and skips buffering entirely; there
     * is no window where a call is buffered after the replay has already run.
     *
     * <p>Calling this again, whether with the same resolver or a different one, simply replaces
     * it; a second call has nothing left to replay, since the first call already drained the
     * buffer.
     */
    public static void install(Resolver newResolver) {
        synchronized (BUFFER_LOCK) {
            resolver = newResolver;
            for (BufferedRecord record : BUFFER) {
                replay(newResolver, record);
            }
            BUFFER.clear();
        }
    }

    /** Resolves a dispatch key to its endpoint entry, or null with no resolver, a disabled module, or an unknown key. */
    public static Object lookup(String module, Object key) {
        if (isDisabledFast(module)) return null;
        Resolver current = resolver;
        if (current == null) return null;
        try {
            return current.lookup(key);
        } catch (Throwable t) {
            logDelegateFailure(module, "lookup", t);
            return null;
        }
    }

    /** Records an endpoint from a framework's own registration hook. Buffers before a resolver is installed. */
    public static Object register(
            String module,
            Object key,
            String verb,
            String verbatimTemplate,
            String contextPath,
            String handlerClass,
            String handlerMethod,
            String handlerDescriptor) {
        if (isDisabledFast(module)) return null;
        Resolver current;
        synchronized (BUFFER_LOCK) {
            current = resolver;
            if (current == null) {
                buffer(
                        BufferedRecord.forRegister(
                                module, key, verb, verbatimTemplate, contextPath, handlerClass, handlerMethod, handlerDescriptor));
                return null;
            }
        }
        try {
            return current.register(key, module, verb, verbatimTemplate, contextPath, handlerClass, handlerMethod, handlerDescriptor);
        } catch (Throwable t) {
            logDelegateFailure(module, "register", t);
            return null;
        }
    }

    /** Records an endpoint the moment a request dispatches to it. Buffers before a resolver is installed. */
    public static Object recordDispatch(
            String module, Object key, String verb, String verbatimTemplate, String contextPath, String handlerClass) {
        if (isDisabledFast(module)) return null;
        Resolver current;
        synchronized (BUFFER_LOCK) {
            current = resolver;
            if (current == null) {
                buffer(BufferedRecord.forDispatch(module, key, verb, verbatimTemplate, contextPath, handlerClass));
                return null;
            }
        }
        try {
            return current.recordDispatch(key, module, verb, verbatimTemplate, contextPath, handlerClass);
        } catch (Throwable t) {
            logDelegateFailure(module, "recordDispatch", t);
            return null;
        }
    }

    /**
     * Like {@link #recordDispatch}, but for a route bridge module reading an identity another
     * instrumentation layer already resolved rather than one it matched itself: the identity
     * might already belong to an endpoint a framework module owns. Returns null and binds nothing
     * when it does; otherwise behaves exactly like {@link #recordDispatch}. Buffers before a
     * resolver is installed, the same as {@link #recordDispatch}.
     */
    public static Object recordDispatchIfUnowned(
            String module, Object key, String verb, String verbatimTemplate, String contextPath, String handlerClass) {
        if (isDisabledFast(module)) return null;
        Resolver current;
        synchronized (BUFFER_LOCK) {
            current = resolver;
            if (current == null) {
                buffer(BufferedRecord.forDispatchIfUnowned(module, key, verb, verbatimTemplate, contextPath, handlerClass));
                return null;
            }
        }
        try {
            return current.recordDispatchIfUnowned(key, module, verb, verbatimTemplate, contextPath, handlerClass);
        } catch (Throwable t) {
            logDelegateFailure(module, "recordDispatchIfUnowned", t);
            return null;
        }
    }

    /**
     * Hands a framework object to a module for it to walk on its own, for a framework whose
     * routes are not readable from a registration hook's own arguments and instead require
     * visiting an object the framework builds internally, such as Spring's {@code
     * RouterFunction}. Buffers before a resolver is installed.
     *
     * <p>Unlike {@link #register} and {@link #recordDispatch}, this does not itself record an
     * endpoint. It only delivers {@code frameworkObject} to the module named by {@code module},
     * which is expected to call {@link #register} itself for whatever it finds. A buffered call
     * holds a strong reference to {@code frameworkObject} until {@link #install} replays it,
     * bounded the same way the rest of the buffer already is.
     */
    public static void declare(String module, Object frameworkObject) {
        if (isDisabledFast(module)) return;
        Resolver current;
        synchronized (BUFFER_LOCK) {
            current = resolver;
            if (current == null) {
                buffer(BufferedRecord.forDeclare(module, frameworkObject));
                return;
            }
        }
        try {
            current.declare(module, frameworkObject);
        } catch (Throwable t) {
            logDelegateFailure(module, "declare", t);
        }
    }

    /** Increments an endpoint's hit count. A no-op for a null entry, which is what every other method above returns on failure. */
    public static void hit(Object entry) {
        if (entry == null) return;
        Resolver current = resolver;
        if (current == null) return;
        try {
            current.hit(entry);
        } catch (Throwable t) {
            logDelegateFailure(null, "hit", t);
        }
    }

    /** Attaches a handler class/method/descriptor to an already-resolved entry. A no-op for a null entry or a disabled module. */
    public static void attachHandler(String module, Object entry, String handlerClass, String handlerMethod, String handlerDescriptor) {
        if (entry == null || isDisabledFast(module)) return;
        Resolver current = resolver;
        if (current == null) return;
        try {
            current.attachHandler(entry, handlerClass, handlerMethod, handlerDescriptor);
        } catch (Throwable t) {
            logDelegateFailure(module, "attachHandler", t);
        }
    }

    /**
     * Disables a module, typically after a helper catches a {@link LinkageError} from a framework
     * version its advice does not match. Every other entry point above short-circuits for a
     * disabled module without reaching the resolver at all. Only the first failure for a given
     * module logs or does anything further; a module already known to be broken does not need a
     * second report.
     */
    public static void moduleFailed(String module, Throwable failure) {
        if (module == null) return;
        if (!FAILURE_LOGGED.add(module)) return;
        DISABLED_MODULES.add(module);
        anyDisabled = true;
        String reason = String.valueOf(failure);
        LOG.log(Level.WARNING, "yukon: endpoint module " + module + " disabled itself: " + reason);
        Resolver current;
        synchronized (BUFFER_LOCK) {
            current = resolver;
            if (current == null) {
                buffer(BufferedRecord.forFailure(module, reason));
                return;
            }
        }
        try {
            current.disableModule(module, reason);
        } catch (Throwable t) {
            logDelegateFailure(module, "disableModule", t);
        }
    }

    /**
     * Sets the functional interfaces whose lambda classes {@link #recordLambdaClass} keeps, by
     * {@link Class#getName()}. An empty set turns recording off. The set replaces any earlier one.
     */
    public static void installHandlerInterfaces(Set<String> interfaceNames) {
        handlerInterfaces = interfaceNames == null || interfaceNames.isEmpty()
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<>(interfaceNames));
    }

    /**
     * Remembers the method a lambda class calls, when the lambda is for a handler interface.
     *
     * <p>The advice on the JDK's lambda factory calls this for every lambda class the JVM spins.
     * A class for any other interface costs one set lookup and is not kept. A method whose owner
     * is itself hidden is not kept either, since its name would join to nothing. An abstract method
     * is not kept, since the method that runs depends on the receiver. Entries are held
     * weakly by the lambda class, so an unloaded lambda class drops out. This never throws.
     */
    public static void recordLambdaClass(Class<?> lambdaClass, Class<?> interfaceClass, MethodHandleInfo implementation) {
        // This runs inside the JDK's lambda factory. A lambda or method reference anywhere on this
        // path would ask that factory for a class while it is still building one, and could recurse
        // without end. Keep this method and everything it calls free of both.
        try {
            if (lambdaClass == null || interfaceClass == null || implementation == null) return;
            if (!handlerInterfaces.contains(interfaceClass.getName())) return;
            Class<?> owner = implementation.getDeclaringClass();
            if (owner.isHidden()) return;
            if (Modifier.isAbstract(implementation.getModifiers())) return;
            LambdaImplementation found = new LambdaImplementation(
                    owner.getName(), implementation.getName(), implementation.getMethodType().toMethodDescriptorString());
            synchronized (LAMBDA_IMPLEMENTATIONS) {
                LAMBDA_IMPLEMENTATIONS.put(lambdaClass, found);
            }
        } catch (Throwable t) {
            if (!lambdaFailureLogged) {
                lambdaFailureLogged = true;
                LOG.log(Level.WARNING, "yukon: could not record a handler lambda's method: " + t);
            }
        }
    }

    /**
     * Returns the method {@code lambdaClass} calls, as {@link #recordLambdaClass} recorded it, or
     * null when nothing was recorded for it. A miss is never filled in with a guess.
     */
    public static LambdaImplementation lambdaImplementation(Class<?> lambdaClass) {
        if (lambdaClass == null) return null;
        synchronized (LAMBDA_IMPLEMENTATIONS) {
            return LAMBDA_IMPLEMENTATIONS.get(lambdaClass);
        }
    }

    /** Whether {@link #moduleFailed} has disabled this module. */
    public static boolean isDisabled(String module) {
        return isDisabledFast(module);
    }

    private static boolean isDisabledFast(String module) {
        return anyDisabled && module != null && DISABLED_MODULES.contains(module);
    }

    /** Appends to the buffer while holding {@link #BUFFER_LOCK}; drops the record and logs once if the buffer is already full. */
    private static void buffer(BufferedRecord record) {
        if (BUFFER.size() >= BUFFER_CAPACITY) {
            if (!bufferOverflowLogged) {
                bufferOverflowLogged = true;
                LOG.log(
                        Level.WARNING,
                        "yukon: endpoint replay buffer is full (" + BUFFER_CAPACITY + "); dropping new endpoint records"
                                + " until a resolver is installed");
            }
            return;
        }
        BUFFER.addLast(record);
    }

    private static void replay(Resolver target, BufferedRecord record) {
        try {
            switch (record.kind) {
                case REGISTER:
                    target.register(
                            record.key,
                            record.module,
                            record.verb,
                            record.verbatimTemplate,
                            record.contextPath,
                            record.handlerClass,
                            record.handlerMethod,
                            record.handlerDescriptor);
                    break;
                case DISPATCH:
                    Object entry =
                            target.recordDispatch(
                                    record.key, record.module, record.verb, record.verbatimTemplate, record.contextPath, record.handlerClass);
                    if (entry != null) {
                        target.hit(entry);
                    }
                    break;
                case DISPATCH_IF_UNOWNED:
                    Object unownedEntry =
                            target.recordDispatchIfUnowned(
                                    record.key, record.module, record.verb, record.verbatimTemplate, record.contextPath, record.handlerClass);
                    if (unownedEntry != null) {
                        target.hit(unownedEntry);
                    }
                    break;
                case DECLARE:
                    target.declare(record.module, record.frameworkObject);
                    break;
                case FAILURE:
                    target.disableModule(record.module, record.reason);
                    break;
            }
        } catch (Throwable t) {
            logDelegateFailure(record.module, "replay", t);
        }
    }

    /** Logs once per (module, operation) pair, since a broken resolver would otherwise log on every request. */
    private static void logDelegateFailure(String module, String operation, Throwable t) {
        String key = operation + ":" + (module == null ? "(none)" : module);
        if (DELEGATE_FAILURE_LOGGED.add(key)) {
            LOG.log(Level.WARNING, "yukon: endpoint resolver threw from " + operation + ": " + t, t);
        }
    }
}
