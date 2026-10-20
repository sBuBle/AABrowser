import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Writes update patches for the app's self-updater: bsdiff 4.3 (Colin Percival, BSD licence)
 * with Deflate in place of bzip2. The format must match the app's update/ApkPatch.kt:
 *
 *     "AABPATCH" | version 1 (byte) | newSize | ctrlSize | diffSize   (longs, big-endian)
 *     ctrl (Deflate: triples of longs add, copy, seek) | diff (Deflate) | extra (Deflate)
 */
object ApkPatchWriter {
    private val MAGIC = "AABPATCH".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1

    fun write(old: ByteArray, new: ByteArray, out: OutputStream) {
        val suffixes = suffixArray(old)

        val ctrl = ByteArrayOutputStream()
        val diff = ByteArrayOutputStream()
        val extra = ByteArrayOutputStream()
        val ctrlOut = DataOutputStream(ctrl)

        val oldSize = old.size
        val newSize = new.size
        var scan = 0
        var len = 0
        var pos = 0
        var lastScan = 0
        var lastPos = 0
        var lastOffset = 0

        while (scan < newSize) {
            var oldScore = 0
            scan += len
            var scsc = scan
            while (scan < newSize) {
                val match = search(suffixes, old, new, scan, 0, oldSize)
                len = match.length
                pos = match.position
                while (scsc < scan + len) {
                    if (scsc + lastOffset < oldSize && old[scsc + lastOffset] == new[scsc]) oldScore++
                    scsc++
                }
                if ((len == oldScore && len != 0) || len > oldScore + 8) break
                if (scan + lastOffset < oldSize && old[scan + lastOffset] == new[scan]) oldScore--
                scan++
            }

            if (len != oldScore || scan == newSize) {
                var s = 0
                var sf = 0
                var lenf = 0
                var i = 0
                while (lastScan + i < scan && lastPos + i < oldSize) {
                    if (old[lastPos + i] == new[lastScan + i]) s++
                    i++
                    if (s * 2 - i > sf * 2 - lenf) {
                        sf = s
                        lenf = i
                    }
                }

                var lenb = 0
                if (scan < newSize) {
                    s = 0
                    var sb = 0
                    i = 1
                    while (scan >= lastScan + i && pos >= i) {
                        if (old[pos - i] == new[scan - i]) s++
                        if (s * 2 - i > sb * 2 - lenb) {
                            sb = s
                            lenb = i
                        }
                        i++
                    }
                }

                if (lastScan + lenf > scan - lenb) {
                    val overlap = (lastScan + lenf) - (scan - lenb)
                    s = 0
                    var ss = 0
                    var lens = 0
                    for (j in 0 until overlap) {
                        if (new[lastScan + lenf - overlap + j] == old[lastPos + lenf - overlap + j]) s++
                        if (new[scan - lenb + j] == old[pos - lenb + j]) s--
                        if (s > ss) {
                            ss = s
                            lens = j + 1
                        }
                    }
                    lenf += lens - overlap
                    lenb -= lens
                }

                for (j in 0 until lenf) diff.write(new[lastScan + j] - old[lastPos + j])
                val extraLength = (scan - lenb) - (lastScan + lenf)
                extra.write(new, lastScan + lenf, extraLength)

                ctrlOut.writeLong(lenf.toLong())
                ctrlOut.writeLong(extraLength.toLong())
                ctrlOut.writeLong(((pos - lenb) - (lastPos + lenf)).toLong())

                lastScan = scan - lenb
                lastPos = pos - lenb
                lastOffset = pos - scan
            }
        }
        ctrlOut.flush()

        val ctrlBlock = deflate(ctrl.toByteArray())
        val diffBlock = deflate(diff.toByteArray())
        val extraBlock = deflate(extra.toByteArray())
        DataOutputStream(out).apply {
            write(MAGIC)
            writeByte(VERSION)
            writeLong(newSize.toLong())
            writeLong(ctrlBlock.size.toLong())
            writeLong(diffBlock.size.toLong())
            write(ctrlBlock)
            write(diffBlock)
            write(extraBlock)
            flush()
        }
    }

    /** Applies a patch in memory, to check that a patch rebuilds the exact APK before publishing. */
    fun apply(old: ByteArray, patch: ByteArray): ByteArray {
        val header = DataInputStream(ByteArrayInputStream(patch))
        val magic = ByteArray(MAGIC.size).also { header.readFully(it) }
        if (!magic.contentEquals(MAGIC) || header.readUnsignedByte() != VERSION) throw IOException("Bad patch")
        val newSize = header.readLong().toInt()
        val ctrlSize = header.readLong().toInt()
        val diffSize = header.readLong().toInt()
        val start = MAGIC.size + 1 + 3 * 8
        val ctrl = DataInputStream(InflaterInputStream(ByteArrayInputStream(patch, start, ctrlSize)))
        val diff = DataInputStream(InflaterInputStream(ByteArrayInputStream(patch, start + ctrlSize, diffSize)))
        val extraStart = start + ctrlSize + diffSize
        val extra = DataInputStream(InflaterInputStream(ByteArrayInputStream(patch, extraStart, patch.size - extraStart)))

        val new = ByteArray(newSize)
        var newPos = 0
        var oldPos = 0
        while (newPos < newSize) {
            val add = ctrl.readLong().toInt()
            val copy = ctrl.readLong().toInt()
            val seek = ctrl.readLong().toInt()
            diff.readFully(new, newPos, add)
            for (i in 0 until add) {
                if (oldPos + i in old.indices) new[newPos + i] = (new[newPos + i] + old[oldPos + i]).toByte()
            }
            newPos += add
            oldPos += add
            extra.readFully(new, newPos, copy)
            newPos += copy
            oldPos += seek
        }
        return new
    }

    private fun deflate(data: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(data) }
        return bytes.toByteArray()
    }

    private class Match(val position: Int, val length: Int)

    private fun matchLength(old: ByteArray, oldStart: Int, new: ByteArray, newStart: Int): Int {
        var i = 0
        while (oldStart + i < old.size && newStart + i < new.size && old[oldStart + i] == new[newStart + i]) i++
        return i
    }

    /** Longest match of new (from newStart) among the sorted suffixes of old, by binary search. */
    private fun search(suffixes: IntArray, old: ByteArray, new: ByteArray, newStart: Int, st: Int, en: Int): Match {
        var start = st
        var end = en
        while (end - start >= 2) {
            val mid = start + (end - start) / 2
            if (compare(old, suffixes[mid], new, newStart) < 0) start = mid else end = mid
        }
        val x = matchLength(old, suffixes[start], new, newStart)
        val y = matchLength(old, suffixes[end], new, newStart)
        return if (x > y) Match(suffixes[start], x) else Match(suffixes[end], y)
    }

    /** memcmp of old (from oldStart) with new (from newStart) over the shorter of the two. */
    private fun compare(old: ByteArray, oldStart: Int, new: ByteArray, newStart: Int): Int {
        val length = minOf(old.size - oldStart, new.size - newStart)
        for (i in 0 until length) {
            val a = old[oldStart + i].toInt() and 0xff
            val b = new[newStart + i].toInt() and 0xff
            if (a != b) return a - b
        }
        return 0
    }

    /** Larsson-Sadakane qsufsort, as in bsdiff: suffix array of [old] with oldSize + 1 entries. */
    private fun suffixArray(old: ByteArray): IntArray {
        val oldSize = old.size
        val sa = IntArray(oldSize + 1)
        val v = IntArray(oldSize + 1)
        val buckets = IntArray(256)
        for (b in old) buckets[b.toInt() and 0xff]++
        for (i in 1 until 256) buckets[i] += buckets[i - 1]
        for (i in 255 downTo 1) buckets[i] = buckets[i - 1]
        buckets[0] = 0
        for (i in 0 until oldSize) {
            val b = old[i].toInt() and 0xff
            buckets[b]++
            sa[buckets[b]] = i
        }
        sa[0] = oldSize
        for (i in 0 until oldSize) v[i] = buckets[old[i].toInt() and 0xff]
        v[oldSize] = 0
        for (i in 1 until 256) if (buckets[i] == buckets[i - 1] + 1) sa[buckets[i]] = -1
        sa[0] = -1

        var h = 1
        while (sa[0] != -(oldSize + 1)) {
            var len = 0
            var i = 0
            while (i < oldSize + 1) {
                if (sa[i] < 0) {
                    len -= sa[i]
                    i -= sa[i]
                } else {
                    if (len != 0) sa[i - len] = -len
                    len = v[sa[i]] + 1 - i
                    split(sa, v, i, len, h)
                    i += len
                    len = 0
                }
            }
            if (len != 0) sa[i - len] = -len
            h += h
        }
        for (i in 0 until oldSize + 1) sa[v[i]] = i
        return sa
    }

    private fun split(sa: IntArray, v: IntArray, start: Int, len: Int, h: Int) {
        if (len < 16) {
            var k = start
            while (k < start + len) {
                var j = 1
                var x = v[sa[k] + h]
                var i = 1
                while (k + i < start + len) {
                    if (v[sa[k + i] + h] < x) {
                        x = v[sa[k + i] + h]
                        j = 0
                    }
                    if (v[sa[k + i] + h] == x) {
                        val tmp = sa[k + j]; sa[k + j] = sa[k + i]; sa[k + i] = tmp
                        j++
                    }
                    i++
                }
                for (m in 0 until j) v[sa[k + m]] = k + j - 1
                if (j == 1) sa[k] = -1
                k += j
            }
            return
        }

        val x = v[sa[start + len / 2] + h]
        var jj = 0
        var kk = 0
        for (i in start until start + len) {
            if (v[sa[i] + h] < x) jj++
            if (v[sa[i] + h] == x) kk++
        }
        jj += start
        kk += jj

        var i = start
        var j = 0
        var k = 0
        while (i < jj) {
            when {
                v[sa[i] + h] < x -> i++
                v[sa[i] + h] == x -> {
                    val tmp = sa[i]; sa[i] = sa[jj + j]; sa[jj + j] = tmp
                    j++
                }
                else -> {
                    val tmp = sa[i]; sa[i] = sa[kk + k]; sa[kk + k] = tmp
                    k++
                }
            }
        }
        while (jj + j < kk) {
            if (v[sa[jj + j] + h] == x) {
                j++
            } else {
                val tmp = sa[jj + j]; sa[jj + j] = sa[kk + k]; sa[kk + k] = tmp
                k++
            }
        }

        if (jj > start) split(sa, v, start, jj - start, h)
        for (m in 0 until kk - jj) v[sa[jj + m]] = kk - 1
        if (jj == kk - 1) sa[jj] = -1
        if (start + len > kk) split(sa, v, kk, start + len - kk, h)
    }
}
