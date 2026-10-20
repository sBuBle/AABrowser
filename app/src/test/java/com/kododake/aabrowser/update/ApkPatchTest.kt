package com.kododake.aabrowser.update

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkPatchTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Step(val add: Long, val copy: Long, val seek: Long)

    /** Writes a patch in the format of buildSrc/ApkPatchWriter. */
    private fun patch(newSize: Int, steps: List<Step>, diff: ByteArray, extra: ByteArray): ByteArray {
        val ctrl = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                steps.forEach {
                    out.writeLong(it.add)
                    out.writeLong(it.copy)
                    out.writeLong(it.seek)
                }
            }
        }.toByteArray()
        val ctrlBlock = deflate(ctrl)
        val diffBlock = deflate(diff)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write("AABPATCH".toByteArray())
                out.writeByte(1)
                out.writeLong(newSize.toLong())
                out.writeLong(ctrlBlock.size.toLong())
                out.writeLong(diffBlock.size.toLong())
                out.write(ctrlBlock)
                out.write(diffBlock)
                out.write(deflate(extra))
            }
        }.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes -> DeflaterOutputStream(bytes).use { it.write(data) } }.toByteArray()

    private fun apply(old: ByteArray, patch: ByteArray): ByteArray {
        val oldFile = temp.newFile().apply { writeBytes(old) }
        val patchFile = temp.newFile().apply { writeBytes(patch) }
        val output = File(temp.root, "out")
        ApkPatch.apply(oldFile, patchFile, output)
        return output.readBytes()
    }

    @Test
    fun rebuildsNewFileFromOldDiffAndExtraBytes() {
        val old = "hello world".toByteArray()
        val new = "hello brave world!".toByteArray()
        val steps = listOf(
            Step(add = 6, copy = 6, seek = 0), // "hello " from old, then "brave "
            Step(add = 5, copy = 1, seek = 0) // "world" from old, then "!"
        )
        val diff = ByteArray(11) // unchanged bytes: all differences are zero
        assertArrayEquals(new, apply(old, patch(new.size, steps, diff, "brave !".toByteArray())))
    }

    @Test
    fun diffBytesAreAddedToOldBytes() {
        val old = byteArrayOf(10, 20, 30)
        val new = byteArrayOf(11, 20, 29)
        val diff = byteArrayOf(1, 0, -1)
        assertArrayEquals(new, apply(old, patch(3, listOf(Step(3, 0, 0)), diff, ByteArray(0))))
    }

    @Test
    fun rejectsSomethingThatIsNotAPatch() {
        val bogus = ByteArray(64) { it.toByte() }
        assertThrows(IOException::class.java) { apply("old".toByteArray(), bogus) }
    }

    @Test
    fun rejectsControlDataRunningPastTheNewFile() {
        val corrupt = patch(4, listOf(Step(add = 100, copy = 0, seek = 0)), ByteArray(100), ByteArray(0))
        assertThrows(IOException::class.java) { apply(ByteArray(100), corrupt) }
    }
}
