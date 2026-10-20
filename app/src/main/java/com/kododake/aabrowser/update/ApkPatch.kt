/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.update

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Rebuilds a new APK from the installed one and a patch, so an update only downloads what changed.
 *
 * The format is bsdiff's (Colin Percival) with Deflate instead of bzip2, so it needs nothing beyond
 * the JDK. Patches are written by the release build (buildSrc/ApkPatchWriter.kt, which must stay
 * in step with this file):
 *
 *     "AABPATCH" (8 bytes) | version (1 byte) | newSize | ctrlSize | diffSize   (longs, big-endian)
 *     ctrl  block: Deflate, ctrlSize bytes, triples of longs (add, copy, seek)
 *     diff  block: Deflate, diffSize bytes, bytes added to the old file's bytes
 *     extra block: Deflate, to the end, bytes copied as they are
 *
 * For each triple: write `add` bytes of old + diff, then `copy` bytes of extra, then move the
 * position in old by `seek`.
 */
object ApkPatch {
    private val MAGIC = "AABPATCH".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Int = 1
    private const val HEADER_SIZE = 8 + 1 + 3 * 8

    /** Applies [patch] to [old], writing the result to [output]. Throws on a malformed patch. */
    fun apply(old: File, patch: File, output: File, maxNewSize: Long = 256L * 1024 * 1024) {
        RandomAccessFile(patch, "r").use { patchFile ->
            val header = ByteArray(HEADER_SIZE).also { patchFile.readFully(it) }
            val headerIn = DataInputStream(header.inputStream())
            val magic = ByteArray(MAGIC.size).also { headerIn.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw IOException("Not an update patch")
            if (headerIn.readUnsignedByte() != VERSION) throw IOException("Unsupported patch version")
            val newSize = headerIn.readLong()
            val ctrlSize = headerIn.readLong()
            val diffSize = headerIn.readLong()
            if (newSize !in 0..maxNewSize || ctrlSize < 0 || diffSize < 0 ||
                HEADER_SIZE + ctrlSize + diffSize > patchFile.length()
            ) {
                throw IOException("Corrupt patch header")
            }

            val ctrlStart = HEADER_SIZE.toLong()
            val diffStart = ctrlStart + ctrlSize
            val extraStart = diffStart + diffSize
            val ctrl = DataInputStream(blockStream(patch, ctrlStart, diffStart))
            val diff = blockStream(patch, diffStart, extraStart)
            val extra = blockStream(patch, extraStart, patchFile.length())

            RandomAccessFile(old, "r").use { oldFile ->
                val oldData = oldFile.channel.map(FileChannel.MapMode.READ_ONLY, 0, oldFile.length())
                val oldSize = oldFile.length()
                BufferedOutputStream(output.outputStream(), 64 * 1024).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var newPos = 0L
                    var oldPos = 0L
                    ctrl.use { diff.use { extra.use {
                        while (newPos < newSize) {
                            val add = ctrl.readLong()
                            val copy = ctrl.readLong()
                            val seek = ctrl.readLong()
                            if (add < 0 || copy < 0 || newPos + add + copy > newSize) {
                                throw IOException("Corrupt patch control data")
                            }

                            var remaining = add
                            while (remaining > 0) {
                                val n = minOf(remaining, buffer.size.toLong()).toInt()
                                readFully(diff, buffer, n)
                                for (i in 0 until n) {
                                    val at = oldPos + i
                                    if (at in 0 until oldSize) {
                                        buffer[i] = (buffer[i] + oldData.get(at.toInt())).toByte()
                                    }
                                }
                                out.write(buffer, 0, n)
                                oldPos += n
                                remaining -= n
                            }
                            newPos += add

                            remaining = copy
                            while (remaining > 0) {
                                val n = minOf(remaining, buffer.size.toLong()).toInt()
                                readFully(extra, buffer, n)
                                out.write(buffer, 0, n)
                                remaining -= n
                            }
                            newPos += copy
                            oldPos += seek
                        }
                    } } }
                }
            }
        }
    }

    /** A Deflate block of [patch] between [start] and [end] (exclusive), decompressed. */
    private fun blockStream(patch: File, start: Long, end: Long): InputStream {
        val raw = RandomAccessFile(patch, "r")
        raw.seek(start)
        val bounded = object : InputStream() {
            private var left = end - start
            override fun read(): Int {
                if (left <= 0) return -1
                left--
                return raw.read()
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = raw.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
            override fun close() = raw.close()
        }
        // An Inflater passed in is not released by close(), so end it here.
        return object : InflaterInputStream(bounded.buffered(), Inflater(), 64 * 1024) {
            override fun close() {
                super.close()
                inf.end()
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw EOFException("Patch ended early")
            read += n
        }
    }
}
