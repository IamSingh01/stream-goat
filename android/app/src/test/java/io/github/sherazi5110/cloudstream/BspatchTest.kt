package io.github.sherazi5110.cloudstream

import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Update patches: what the `bsdiff` tool (the GitHub builds) makes, Bspatch turns back into the new file. */
class BspatchTest {
    @Test fun rebuildsTheNewFile() {
        val tool = listOf("/usr/bin/bsdiff", "/usr/local/bin/bsdiff", "/opt/homebrew/bin/bsdiff").firstOrNull { File(it).canExecute() }
        assumeTrue("bsdiff isn't installed", tool != null)
        val dir = kotlin.io.path.createTempDirectory("bspatch").toFile()
        val rnd = Random(7)
        val old = rnd.nextBytes(3_000_000)
        // A changed middle, a moved block and new data at the end, like a new build of an APK.
        val new = old.copyOfRange(0, 1_000_000) + rnd.nextBytes(50_000) + old.copyOfRange(2_000_000, 3_000_000) +
            old.copyOfRange(1_000_000, 2_000_000).also { for (i in it.indices step 997) it[i] = (it[i] + 1).toByte() } + rnd.nextBytes(20_000)
        val o = File(dir, "old").apply { writeBytes(old) }
        val n = File(dir, "new").apply { writeBytes(new) }
        val p = File(dir, "p")
        assumeTrue(ProcessBuilder(tool, o.path, n.path, p.path).start().waitFor() == 0)
        val out = File(dir, "out")
        Bspatch.apply(o, p, out)
        assertArrayEquals(new, out.readBytes())
        dir.deleteRecursively()
    }
}
