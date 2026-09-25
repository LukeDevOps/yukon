@file:JvmMultifileClass
@file:JvmName("DemoText")

package io.github.lukedevops.demo.server

/**
 * The other file of the multi-file facade `DemoText`. Nothing calls this function, so its part,
 * `DemoText__DemoTextFarewellKt`, never loads, and the report names it by this file. See ADR 0041.
 */
fun farewellNote(): String = "come back soon"
