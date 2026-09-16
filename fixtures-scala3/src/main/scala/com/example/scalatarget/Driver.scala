package com.example.scalatarget

/**
 * Known call patterns the integration tests invoke reflectively, so an omission count can be
 * checked against an exact number of real calls rather than only against `$default`'s own
 * reflective invocation.
 */
object Driver {
  def callSimpleAllOmitted(): Int = new Simple().f(1)
  def callSimpleNoneOmitted(): Int = new Simple().f(1, 2, "y")
  def callSimpleOverload(): Int = new Simple().f(1, 10L)

  def callCurried(): Int = new Curried().g(1)()

  def callByName(): Int = new ByNameHost().h()

  def callGeneric(): AnyRef = new Generic().firstOrNull()

  def callFinalMethod(): Int = new FinalMethodHost().finalF(1)

  def callPrivateViaPublic(): Int = new PrivateMethodHost().callPriv(1)

  def callTwoLists(): Int = new TwoListsHost().twoLists()()

  def callSameNameDiffArity(): Int = new SameNameDiffArityHost().sameNameDiffArity(1, 2)

  def callVararg(): Int = new VarargHost().withVararg()()

  def callTraitDefault(): Int = new TrImpl().t()

  def callObjectDefault(): Int = Obj.m(1)

  def callCaseClassApply(): Cc = Cc.apply(1)
  def callCaseClassConstructor(): Cc = new Cc(9)

  /** Calls through a `Plain`-typed reference whose runtime class overrides only `f`. */
  def callThroughPlainOverridesOnly(): Int = {
    val p: Plain = new PlainOverridesOnly()
    p.f()
  }

  def callThroughPlainOverridesBoth(): Int = {
    val p: Plain = new PlainOverridesBoth()
    p.f()
  }
}
