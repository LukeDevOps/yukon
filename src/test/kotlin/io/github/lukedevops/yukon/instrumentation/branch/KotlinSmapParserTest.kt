package io.github.lukedevops.yukon.instrumentation.branch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves [KotlinSmapParser] against a real SMAP read with `javap -v` from
 * `demo/build/classes/kotlin/main/io/github/lukedevops/demo/server/DemoServerMainKt.class`, and
 * against the malformed shapes [KotlinSmap.originOf] must fall back to "not a copy" for.
 */
class KotlinSmapParserTest {
    private val demoServerMainSmap =
        "SMAP\nDemoServerMain.kt\nKotlin\n*S Kotlin\n*F\n+ 1 DemoServerMain.kt\n" +
            "io/github/lukedevops/demo/server/DemoServerMainKt\n+ 2 _Collections.kt\n" +
            "kotlin/collections/CollectionsKt___CollectionsKt\n+ 3 fake.kt\nkotlin/jvm/internal/FakeKt\n*L\n" +
            "1#1,133:1\n78#1:134\n78#1:135\n1563#2:136\n1634#2,3:137\n295#2,2:140\n1#3:142\n*S KotlinDebug\n*F\n" +
            "+ 1 DemoServerMain.kt\nio/github/lukedevops/demo/server/DemoServerMainKt\n*L\n" +
            "65#1:134\n67#1:135\n119#1:136\n119#1:137,3\n120#1:140,2\n*E\n"

    @Test
    fun `the identity range is not a copy`() {
        val smap = KotlinSmapParser.parse(demoServerMainSmap)

        for (line in listOf(1, 50, 133)) assertNull(smap.originOf(line), "output line $line is the file's own code")
    }

    @Test
    fun `two call sites of a same-file inline function both map to its declaration line`() {
        val smap = KotlinSmapParser.parse(demoServerMainSmap)

        assertEquals(
            SmapOrigin(78, "io.github.lukedevops.demo.server.DemoServerMainKt", "DemoServerMain.kt"),
            smap.originOf(134),
        )
        assertEquals(
            SmapOrigin(78, "io.github.lukedevops.demo.server.DemoServerMainKt", "DemoServerMain.kt"),
            smap.originOf(135),
        )
    }

    @Test
    fun `an origin carries its SMAP file entry's file name beside the origin class`() {
        val smap = KotlinSmapParser.parse(demoServerMainSmap)

        assertEquals("_Collections.kt", smap.originOf(136)?.sourceFile)
        assertEquals("DemoServerMain.kt", smap.originOf(134)?.sourceFile)
        val bare = KotlinSmapParser.parse("SMAP\nFoo.kt\nKotlin\n*S Kotlin\n*F\n1 Foo.kt\n2 Bar.kt\n*L\n1#1,10:1\n7#2:20\n*E\n")
        assertEquals(SmapOrigin(7, "Bar.kt", "Bar.kt"), bare.originOf(20), "a bare entry has no path, so its name is both")
    }

    @Test
    fun `a single-line entry with no explicit repeat count maps one output line`() {
        val smap = KotlinSmapParser.parse(demoServerMainSmap)

        assertEquals(SmapOrigin(1563, "kotlin.collections.CollectionsKt___CollectionsKt", "_Collections.kt"), smap.originOf(136))
    }

    @Test
    fun `a multi-line entry maps a contiguous run of output lines to a contiguous run of input lines`() {
        val smap = KotlinSmapParser.parse(demoServerMainSmap)

        assertEquals(SmapOrigin(1634, "kotlin.collections.CollectionsKt___CollectionsKt", "_Collections.kt"), smap.originOf(137))
        assertEquals(SmapOrigin(1635, "kotlin.collections.CollectionsKt___CollectionsKt", "_Collections.kt"), smap.originOf(138))
        assertEquals(SmapOrigin(1636, "kotlin.collections.CollectionsKt___CollectionsKt", "_Collections.kt"), smap.originOf(139))
    }

    @Test
    fun `a missing line file id inherits the previous entry's file id`() {
        // 295#2,2:140 carries an explicit file id 2; the entry that follows it in a hand-built
        // SMAP with the file id omitted must still resolve against file 2, not fall back to 1.
        val debug =
            "SMAP\nFoo.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Foo.kt\ncom/example/FooKt\n" +
                "+ 2 Bar.kt\ncom/example/BarKt\n*L\n1#1,10:1\n50#2:20\n60:21\n*E\n"

        val smap = KotlinSmapParser.parse(debug)

        assertEquals(SmapOrigin(60, "com.example.BarKt", "Bar.kt"), smap.originOf(21), "file id 2 must be inherited from the entry above")
    }

    @Test
    fun `an output line increment gives each input line a block of consecutive output lines`() {
        // JSR-045: "10#2,2:100,3" maps input line 10 to output lines 100 to 102 and input line
        // 11 to 103 to 105. Every line inside a block resolves to its input line, not only the
        // first one.
        val debug =
            "SMAP\nFoo.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Foo.kt\ncom/example/FooKt\n" +
                "+ 2 Bar.kt\ncom/example/BarKt\n*L\n1#1,10:1\n10#2,2:100,3\n*E\n"

        val smap = KotlinSmapParser.parse(debug)

        for (line in 100..102) assertEquals(SmapOrigin(10, "com.example.BarKt", "Bar.kt"), smap.originOf(line), "output line $line")
        for (line in 103..105) assertEquals(SmapOrigin(11, "com.example.BarKt", "Bar.kt"), smap.originOf(line), "output line $line")
        assertNull(smap.originOf(106), "past the last block")
    }

    @Test
    fun `kotlinc's own fake line for an inline-lambda marker resolves to its own synthetic file`() {
        val smap = KotlinSmapParser.parse(demoServerMainSmap)

        assertEquals(SmapOrigin(1, "kotlin.jvm.internal.FakeKt", "fake.kt"), smap.originOf(142))
    }

    @Test
    fun `an output line covered by no entry is not a copy`() {
        val smap = KotlinSmapParser.parse(demoServerMainSmap)

        assertNull(smap.originOf(9000))
    }

    @Test
    fun `a null debug string yields no ranges`() {
        val smap = KotlinSmapParser.parse(null)

        assertNull(smap.originOf(1))
        assertNull(smap.originOf(134))
    }

    @Test
    fun `an empty debug string yields no ranges`() {
        val smap = KotlinSmapParser.parse("")

        assertNull(smap.originOf(1))
    }

    @Test
    fun `an SMAP whose only stratum is not Kotlin yields no ranges`() {
        val debug = "SMAP\nFoo.java\nJSP\n*S JSP\n*F\n+ 1 Foo.jsp\nFoo_jsp\n*L\n1,10:1\n*E\n"

        val smap = KotlinSmapParser.parse(debug)

        assertNull(smap.originOf(1))
        assertNull(smap.originOf(5))
    }

    @Test
    fun `the KotlinDebug stratum is ignored, even for an output line only it covers`() {
        // The Kotlin stratum's own *L section only covers output lines 1-5. Output line 50 is
        // covered only by the KotlinDebug stratum that follows. If parsing failed to stop at
        // "*S KotlinDebug", that entry would leak in and originOf(50) would resolve against it.
        val debug =
            "SMAP\nFoo.kt\nKotlin\n*S Kotlin\n*F\n+ 1 Foo.kt\ncom/example/FooKt\n*L\n1#1,5:1\n" +
                "*S KotlinDebug\n*F\n+ 1 Foo.kt\ncom/example/FooKt\n*L\n99#1:50\n*E\n"

        val smap = KotlinSmapParser.parse(debug)

        assertNull(smap.originOf(50))
    }
}
