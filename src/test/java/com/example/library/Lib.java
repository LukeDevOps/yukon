package com.example.library;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Supplier;

/**
 * Stand-in library types for the reference tests: a package outside every include rule the
 * reference fixtures are analysed with. Each nested type is named by exactly one construct in
 * {@code com.example.target.ReferenceTarget}, so a test can say which construct produced which
 * reference.
 */
public final class Lib {
    private Lib() {}

    public enum Mode { ON, OFF }

    public enum MethodMode { ON, OFF }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Nested {}

    @Retention(RetentionPolicy.RUNTIME)
    public @interface MethodNested {}

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Marker {
        Mode mode();

        Class<?> type();

        Nested nested();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface MethodAnno {
        MethodMode mode();

        Class<?> type();

        MethodNested nested();
    }

    /** Class retention, so javac writes it to RuntimeInvisibleParameterAnnotations: not a reference. */
    @Retention(RetentionPolicy.CLASS)
    public @interface ParamAnno {}

    @Retention(RetentionPolicy.RUNTIME)
    public @interface VisibleParamAnno {
        Class<?> type();
    }

    public static class ParamClassValue {}

    /**
     * Class retention, so it lands in a RuntimeInvisible*Annotations attribute on every element it
     * is put on: neither it nor anything its values name is a reference.
     */
    @Retention(RetentionPolicy.CLASS)
    public @interface Invisible {
        InvisibleMode mode();

        Class<?> type();

        InvisibleNested nested();
    }

    public enum InvisibleMode { ON, OFF }

    public static class InvisibleClassValue {}

    @Retention(RetentionPolicy.RUNTIME)
    public @interface InvisibleNested {}

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.TYPE_USE)
    public @interface InvisibleTypeUse {}

    @Retention(RetentionPolicy.RUNTIME)
    public @interface FieldAnno {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface TypeUseReturn {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface TypeUseCast {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    public @interface TypeUseField {}

    public static class ClassValue {}

    public static class MethodClassValue {}

    public abstract static class Base {}

    public interface Iface {}

    public static class FieldType {}

    public static class FieldGeneric {}

    public static class New {}

    public static class Cast {}

    public static class InstanceOf {}

    public static class ArrayElement {}

    public static class MultiArray {}

    public static class Literal {}

    public static class ArrayLiteral {}

    public static class StaticOwner {
        public static int compute() {
            return 1;
        }

        public static void accept(Accepted accepted) {}
    }

    public static class Accepted {}

    public static class FieldOwner {
        public static int value = 1;

        public static FieldTyped typed;
    }

    public static class FieldTyped {}

    public static class Caught extends RuntimeException {}

    public static class Returned {}

    public static class Param {}

    public static class Thrown extends Exception {}

    public static class GenericParam {}

    public static class RefTarget {
        public static Object make() {
            return null;
        }
    }

    public interface Producer extends Supplier<Object> {}

    public static class AbstractReturn {}

    public static class AbstractParam {}

    public static class LambdaBody {
        public static void run() {}
    }

    public static class Secret {}

    public static class DefaultValue {
        public static Object make() {
            return null;
        }
    }

    public static class BodyRef {
        public static void run() {}
    }

    public static class Helper {}

    public static class Widget {}

    public static class DefaultElementValue {}
}
