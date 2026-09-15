---
status: accepted
---

# JAX-RS annotations are inherited by the spec's rules, plus Jersey's class-level `@Path` only where Jersey is present, and only onto methods the concrete class declares

The JAX-RS module resolves annotations for a concrete resource class the way the specification's "Annotation Inheritance" section says: a method with no JAX-RS annotation of its own inherits the annotations of the method it overrides or implements, superclass chain before interfaces, first interface in declaration order when two disagree (logged once at WARNING). Any JAX-RS annotation on the method itself, even `@Produces`, switches inheritance off for that method. Class-level `@Path` is not inherited per spec, but Jersey honours one declared on a superclass or interface, so the module does too, only when Jersey is present on the resource class's loader; otherwise it logs once at INFO and leaves the class prefix out. Inheritance applies only to methods the concrete class declares itself.

## Considered options

- Jersey's rules everywhere. Rejected: RESTEasy reads `@Path` off the concrete class alone, so an interface-declared resource on RESTEasy is not a resource at all, and declaring its endpoints would be a false "never called".
- Spec rules only. Rejected: the common shape is an API interface carrying `@Path` at class level plus method annotations, with a bare implementing class. On Jersey that serves, and spec-only rules would report its templates without the class prefix.
- Inheriting onto a concrete method body the subclass does not override. Rejected: the body lives in the superclass, the advice key is the declaring type, and two subclasses inheriting one body under different class-level paths would be two endpoints behind one key. Declaring such an endpoint on the subclass would register it with no advice behind it, a false "never called". An interface method always meets a body declared in the implementing class, so the common case is unaffected.

## Consequences

- The type matcher walks supertypes for every class load, bounded by skipping bootstrap-loader classes, where no resource can live. Descriptions are cached per loader by ByteBuddy's type pool, so each supertype resolves once.
- Which runtime is present changes what is declared. That is deliberate: the module reports what the runtime in front of it would serve.
