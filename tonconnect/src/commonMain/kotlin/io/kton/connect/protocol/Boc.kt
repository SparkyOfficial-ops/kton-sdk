package io.kton.connect.protocol

import io.kton.connect.crypto.sha256

internal class Cell(val bits: BooleanArray, val refs: List<Cell>) {
    val depth: Int by lazy { if (refs.isEmpty()) 0 else refs.maxOf { it.depth } + 1 }
    val hash: ByteArray by lazy { sha256(representation()) }

    private fun representation(): ByteArray {
        val data = topUp()
        val d2 = bits.size / 8 + data.size
        val out = ByteArray(2 + data.size + refs.size * 34)
        out[0] = refs.size.toByte()
        out[1] = d2.toByte()
        data.copyInto(out, 2)
        var offset = 2 + data.size
        for (ref in refs) {
            out[offset] = (ref.depth ushr 8).toByte()
            out[offset + 1] = ref.depth.toByte()
            offset += 2
        }
        for (ref in refs) {
            ref.hash.copyInto(out, offset)
            offset += 32
        }
        return out
    }

    private fun topUp(): ByteArray {
        val length = (bits.size + 7) / 8
        val data = ByteArray(length)
        for (index in bits.indices) {
            if (!bits[index]) continue
            val shift = 7 - (index % 8)
            data[index / 8] = (data[index / 8].toInt() or (1 shl shift)).toByte()
        }
        if (bits.size % 8 != 0) {
            val shift = 7 - (bits.size % 8)
            data[bits.size / 8] = (data[bits.size / 8].toInt() or (1 shl shift)).toByte()
        }
        return data
    }
}

internal class BitBuilder {
    private val bits = ArrayList<Boolean>()

    fun writeBit(value: Boolean) {
        bits.add(value)
    }

    fun writeUint(value: Long, width: Int) {
        require(width in 1..63)
        for (shift in width - 1 downTo 0) writeBit(((value ushr shift) and 1L) != 0L)
    }

    fun writeBytes(value: ByteArray) {
        for (byte in value) {
            for (shift in 7 downTo 0) writeBit(((byte.toInt() ushr shift) and 1) != 0)
        }
    }

    fun toCell(refs: List<Cell> = emptyList()): Cell = Cell(BooleanArray(bits.size) { bits[it] }, refs)
}

internal class Slice(private val cell: Cell) {
    private var bit = 0
    private var ref = 0

    fun loadBit(): Boolean {
        require(bit < cell.bits.size) { "Cell underflow" }
        return cell.bits[bit++]
    }

    fun skip(count: Int) {
        require(bit + count <= cell.bits.size) { "Cell underflow" }
        bit += count
    }

    fun loadBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        for (index in 0 until count) {
            var value = 0
            repeat(8) { value = (value shl 1) or if (loadBit()) 1 else 0 }
            out[index] = value.toByte()
        }
        return out
    }

    fun loadRef(): Cell {
        require(ref < cell.refs.size) { "Cell reference underflow" }
        return cell.refs[ref++]
    }
}

internal fun parseBoc(data: ByteArray): List<Cell> {
    require(data.size > 10) { "BOC is too short" }
    val magic = ((data[0].toInt() and 0xFF) shl 24) or
        ((data[1].toInt() and 0xFF) shl 16) or
        ((data[2].toInt() and 0xFF) shl 8) or
        (data[3].toInt() and 0xFF)
    require(magic == 0xb5ee9c72.toInt()) { "BOC magic is missing" }
    val reader = Cursor(data, 4)
    val flags = reader.u8()
    val hasIndex = flags and 0x80 != 0
    val hasCrc = flags and 0x40 != 0
    require(flags and 0x20 == 0) { "BOC cache bits are not used by wallet state" }
    require(flags and 0x18 == 0) { "Unsupported BOC flags" }
    val sizeBytes = flags and 0x07
    require(sizeBytes in 1..4) { "Invalid BOC size" }
    val offsetBytes = reader.uint(sizeBytes).toInt()
    val cellCount = reader.uint(sizeBytes).toInt()
    val rootCount = reader.uint(sizeBytes).toInt()
    val absent = reader.uint(sizeBytes).toInt()
    require(absent == 0 && cellCount > 0 && rootCount > 0) { "BOC has no cells" }
    val payloadSize = reader.uint(offsetBytes).toInt()
    val roots = IntArray(rootCount) { reader.uint(sizeBytes).toInt() }
    if (hasIndex) reader.skip(cellCount * offsetBytes)
    val payloadEnd = reader.position + payloadSize
    require(payloadEnd <= data.size) { "BOC payload is truncated" }
    if (hasCrc) {
        require(payloadEnd + 4 <= data.size) { "BOC checksum is missing" }
        val expected = crc32c(data, payloadEnd)
        val actual = (data[payloadEnd].toInt() and 0xFF) or
            ((data[payloadEnd + 1].toInt() and 0xFF) shl 8) or
            ((data[payloadEnd + 2].toInt() and 0xFF) shl 16) or
            ((data[payloadEnd + 3].toInt() and 0xFF) shl 24)
        require(expected == actual) { "BOC checksum mismatch" }
    }
    val raw = Array(cellCount) { readRawCell(reader, sizeBytes) }
    require(reader.position == payloadEnd) { "BOC cell sizes do not add up" }
    val linked = arrayOfNulls<Cell>(cellCount)
    fun link(index: Int, stack: MutableSet<Int>): Cell {
        linked[index]?.let { return it }
        require(index !in stack) { "BOC cell graph has a cycle" }
        stack.add(index)
        val cell = Cell(raw[index].bits, raw[index].refs.map { link(it, stack) })
        stack.remove(index)
        linked[index] = cell
        return cell
    }
    return roots.map { link(it, mutableSetOf()) }
}

internal fun Cell.toBoc(): ByteArray {
    val order = ArrayList<Cell>()
    fun indexOf(cell: Cell): Int {
        val found = order.indexOfFirst { it === cell }
        return found
    }
    fun walk(cell: Cell) {
        if (indexOf(cell) >= 0) return
        order.add(cell)
        cell.refs.forEach(::walk)
    }
    walk(this)
    require(order.size <= 255) { "BOC has too many cells" }
    val payloads = order.map { cell ->
        val body = cell.topPacked()
        val out = ByteArray(2 + body.size + cell.refs.size)
        out[0] = cell.refs.size.toByte()
        out[1] = (cell.bits.size / 8 + body.size).toByte()
        body.copyInto(out, 2)
        cell.refs.forEachIndexed { refIndex, ref ->
            out[2 + body.size + refIndex] = indexOf(ref).toByte()
        }
        out
    }
    val total = payloads.sumOf { it.size }
    val offsetBytes = when {
        total < 256 -> 1
        total < 65536 -> 2
        else -> 4
    }
    val header = ByteArray(4 + 1 + 4 + offsetBytes + 1)
    header[0] = 0xb5.toByte()
    header[1] = 0xee.toByte()
    header[2] = 0x9c.toByte()
    header[3] = 0x72.toByte()
    header[4] = (0x40 or 0x01).toByte()
    var cursor = 5
    header[cursor++] = offsetBytes.toByte()
    header[cursor++] = order.size.toByte()
    header[cursor++] = 1
    header[cursor++] = 0
    writeUint(header, cursor, offsetBytes, total.toLong())
    cursor += offsetBytes
    header[cursor] = 0
    val body = ByteArray(header.size + total)
    header.copyInto(body)
    var offset = header.size
    for (payload in payloads) {
        payload.copyInto(body, offset)
        offset += payload.size
    }
    val crc = crc32c(body, body.size)
    val out = ByteArray(body.size + 4)
    body.copyInto(out)
    out[body.size] = crc.toByte()
    out[body.size + 1] = (crc ushr 8).toByte()
    out[body.size + 2] = (crc ushr 16).toByte()
    out[body.size + 3] = (crc ushr 24).toByte()
    return out
}

private class RawCell(val bits: BooleanArray, val refs: IntArray)

private fun readRawCell(reader: Cursor, sizeBytes: Int): RawCell {
    val descriptor = reader.u8()
    val bitsDescriptor = reader.u8()
    require(descriptor and 0x08 == 0) { "Exotic cells are not used by standard wallets" }
    require(descriptor and 0xE0 == 0) { "Higher cell levels are not used by wallet state" }
    val refCount = descriptor and 0x07
    val dataLength = (bitsDescriptor ushr 1) + (bitsDescriptor and 1)
    val data = reader.bytes(dataLength)
    val refs = IntArray(refCount) { reader.uint(sizeBytes).toInt() }
    return RawCell(unpackBits(data, bitsDescriptor), refs)
}

private fun unpackBits(data: ByteArray, bitsDescriptor: Int): BooleanArray {
    val end = if (bitsDescriptor and 1 == 0) {
        data.size * 8
    } else {
        var last = data.size * 8 - 1
        while (last >= 0 && !bitAt(data, last)) last--
        require(last >= 0) { "Cell completion tag is missing" }
        last
    }
    return BooleanArray(end) { bitAt(data, it) }
}

private fun bitAt(data: ByteArray, index: Int): Boolean {
    val shift = 7 - (index % 8)
    return (data[index / 8].toInt() and (1 shl shift)) != 0
}

private fun Cell.topPacked(): ByteArray {
    val length = (bits.size + 7) / 8
    val data = ByteArray(length)
    for (index in bits.indices) {
        if (!bits[index]) continue
        val shift = 7 - (index % 8)
        data[index / 8] = (data[index / 8].toInt() or (1 shl shift)).toByte()
    }
    if (bits.size % 8 != 0) {
        val shift = 7 - (bits.size % 8)
        data[bits.size / 8] = (data[bits.size / 8].toInt() or (1 shl shift)).toByte()
    }
    return data
}

private class Cursor(private val data: ByteArray, var position: Int) {
    fun u8(): Int {
        require(position < data.size) { "BOC is truncated" }
        return data[position++].toInt() and 0xFF
    }

    fun uint(bytes: Int): Long {
        var value = 0L
        repeat(bytes) { value = (value shl 8) or u8().toLong() }
        return value
    }

    fun bytes(count: Int): ByteArray {
        require(position + count <= data.size) { "BOC is truncated" }
        return data.copyOfRange(position, position + count).also { position += count }
    }

    fun skip(count: Int) {
        require(position + count <= data.size) { "BOC is truncated" }
        position += count
    }
}

private fun writeUint(out: ByteArray, offset: Int, bytes: Int, value: Long) {
    for (index in bytes - 1 downTo 0) {
        out[offset + (bytes - 1 - index)] = (value ushr (8 * index)).toByte()
    }
}

internal fun crc32c(data: ByteArray, length: Int = data.size): Int {
    var crc = -1
    for (index in 0 until length) {
        crc = crc xor (data[index].toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor 0x82F63B78.toInt() else crc ushr 1
        }
    }
    return crc.inv()
}
