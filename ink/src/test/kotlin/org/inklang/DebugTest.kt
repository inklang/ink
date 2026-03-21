package org.inklang

import org.inklang.lang.tokenize
import kotlin.test.Test

class DebugTest {
    @Test
    fun debugTokenization() {
        val source = """
fun add(a, b) {
    return a + b;
}
print(add(10, 20));
""".trimIndent()
        val tokens = tokenize(source)
        tokens.forEachIndexed { i, t -> println("$i: $t") }
    }
}
