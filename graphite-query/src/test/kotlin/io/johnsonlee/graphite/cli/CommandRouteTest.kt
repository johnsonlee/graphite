package io.johnsonlee.graphite.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandRouteTest {
    @Test
    fun `recognizes only server commands that will execute`() {
        assertTrue(routesToNativeServe(arrayOf("serve", "--data", "absent path")))
        assertTrue(routesToNativeServe(arrayOf("serve", "--data", "@@literal")))
        for (args in listOf(
            emptyArray(), arrayOf("--help", "serve"), arrayOf("--version", "serve"),
            arrayOf("serve", "--help"), arrayOf("serve", "--version"),
            arrayOf("serve", "--port", "bad"), arrayOf("serve", "--unknown"),
            arrayOf("build", "missing.jar"), arrayOf("query", "--help")
        )) {
            assertFalse(routesToNativeServe(args), args.contentToString())
        }
    }

    @Test
    fun `uses actual Picocli nested argument files without running server or build`() {
        val dir = Files.createTempDirectory("graphite-route-")
        try {
            val child = dir.resolve("nested.args")
            val top = dir.resolve("top.args")
            val data = dir.resolve("must-not-be-created")
            Files.writeString(child, "serve --data '${data}' --port 0\n")
            Files.writeString(top, "@${child}\n")
            assertTrue(routesToNativeServe(arrayOf("@${top}")))
            assertFalse(Files.exists(data))
            assertFalse(routesToNativeServe(arrayOf("--help", "@${top}")))
            Files.writeString(child, "build '${dir.resolve("missing.jar")}'\n")
            assertFalse(routesToNativeServe(arrayOf("@${top}")))
            Files.writeString(child, "serve --port invalid\n")
            assertFalse(routesToNativeServe(arrayOf("@${top}")))
            assertFalse(Files.exists(data))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
