package com.example.target;

import com.example.library.Lib;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An annotation interface whose element default names a library class. The default lives in the
 * element's AnnotationDefault attribute, which carries no visibility flag, and the element method
 * is abstract, so the reference belongs to the class.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface ReferenceAnnotationWithDefault {
    Class<?> type() default Lib.DefaultElementValue.class;
}
