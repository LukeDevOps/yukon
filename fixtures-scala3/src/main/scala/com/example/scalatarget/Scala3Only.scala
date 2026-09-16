package com.example.scalatarget

/** An extension method with a default; the receiver is an ordinary first JVM parameter. */
object Extensions {
  extension (n: Int)
    def pad(width: Int = 5): String = {
      val s = n.toString
      if (s.length >= width) s else ("0" * (width - s.length)) + s
    }
}

/** An `inline def` with a default: its getter is emitted and called, but the function itself has no bytecode. */
object InlineHost {
  inline def inlineF(a: Int, b: Int = 7): Int = a + b
}

/** Scala-3-only call patterns, kept separate from [[Driver]] since the scala2 fixture has no equivalents. */
object Driver3 {
  def callExtensionAllOmitted(): String = {
    import Extensions.pad
    (5).pad()
  }

  def callExtensionNoneOmitted(): String = {
    import Extensions.pad
    (5).pad(3)
  }

  def callInline(): Int = InlineHost.inlineF(1)
}
