package com.example.target;

import com.example.library.Lib;
import com.example.other.OtherTarget;
import java.util.List;

/**
 * One method per kind of reference ADR 0030 counts, each naming its own {@link Lib} type, so a test
 * can pin which construct produced which reference. The class itself carries the class-level
 * kinds: a superclass, an interface, a class annotation with enum, class and nested-annotation
 * values, and fields with a type, a generic signature, an annotation and a type-use annotation.
 * Every {@code Lib.Invisible*} type is named only from class-retention annotations, on the class, a
 * field, a method, a parameter and in type-use positions, so none of them is a reference.
 */
@Lib.Marker(mode = Lib.Mode.ON, type = Lib.ClassValue.class, nested = @Lib.Nested)
@Lib.Invisible(mode = Lib.InvisibleMode.ON, type = Lib.InvisibleClassValue.class, nested = @Lib.InvisibleNested)
public class ReferenceTarget extends Lib.Base implements Lib.Iface {
    @Lib.FieldAnno
    public Lib.FieldType field;

    public List<Lib.FieldGeneric> genericField;

    public @Lib.TypeUseField Object typeUseField;

    @Lib.Invisible(mode = Lib.InvisibleMode.OFF, type = Lib.InvisibleClassValue.class, nested = @Lib.InvisibleNested)
    public Object invisibleField;

    public @Lib.InvisibleTypeUse Object invisibleTypeUseField;

    public Object newInstance() {
        return new Lib.New();
    }

    public Object cast(Object o) {
        return (Lib.Cast) o;
    }

    public boolean instanceOf(Object o) {
        return o instanceof Lib.InstanceOf;
    }

    public Object newArray() {
        return new Lib.ArrayElement[1];
    }

    public Object multiArray() {
        return new Lib.MultiArray[1][2];
    }

    public Object classLiteral() {
        return Lib.Literal.class;
    }

    public Object arrayClassLiteral() {
        return Lib.ArrayLiteral[].class;
    }

    public int staticCall() {
        return Lib.StaticOwner.compute();
    }

    public int staticField() {
        return Lib.FieldOwner.value;
    }

    public void callDescriptor() {
        Lib.StaticOwner.accept(null);
    }

    public Object fieldDescriptor() {
        return Lib.FieldOwner.typed;
    }

    public void catches() {
        try {
            Lib.StaticOwner.compute();
        } catch (Lib.Caught e) {
            // The handler's type is the reference under test; there is nothing to do with it.
        }
    }

    public Lib.Returned signature(Lib.Param p) throws Lib.Thrown {
        return null;
    }

    public void generic(List<Lib.GenericParam> list) {}

    @Lib.MethodAnno(mode = Lib.MethodMode.OFF, type = Lib.MethodClassValue.class, nested = @Lib.MethodNested)
    public void annotated(@Lib.ParamAnno @Lib.VisibleParamAnno(type = Lib.ParamClassValue.class) int x) {}

    @Lib.Invisible(mode = Lib.InvisibleMode.ON, type = Lib.InvisibleClassValue.class, nested = @Lib.InvisibleNested)
    public Object invisiblyAnnotated(
        @Lib.Invisible(mode = Lib.InvisibleMode.OFF, type = Lib.InvisibleClassValue.class, nested = @Lib.InvisibleNested) Object p
    ) {
        return (@Lib.InvisibleTypeUse String) p;
    }

    public @Lib.InvisibleTypeUse Object invisibleTypeUseReturn() {
        return null;
    }

    public @Lib.TypeUseReturn Object typeUseReturn() {
        return null;
    }

    public Object typeUseCast(Object o) {
        return (@Lib.TypeUseCast String) o;
    }

    public Lib.Producer methodReference() {
        return Lib.RefTarget::make;
    }

    public Runnable lambda() {
        return () -> Lib.LambdaBody.run();
    }

    public int primitivesAndSelf(int a, long[] b, ReferenceTarget self, ReferenceTarget[] selves) {
        return a;
    }

    public int inScope(OtherTarget other) {
        other.doSomething();
        return 0;
    }
}
