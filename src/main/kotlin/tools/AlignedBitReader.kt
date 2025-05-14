package org.example.tools

/**
 * 从 ByteArray 的任意位偏移开始，按字节对齐方式读取数据
 * @param payload 原始字节数组
 * @param startBitOffset 起始位偏移（0 表示第一个字节的最高位，7 表示最低位）
 */
class AlignedBitReader(private val payload: ByteArray, private var startBitOffset: Int = 0) {
    private var currentByteIndex = 0
    private var currentBitOffset = startBitOffset

    init {
        require(startBitOffset in 0..7) { "startBitOffset 必须在 0~7 范围内" }
        require(payload.isNotEmpty()) { "payload 不能为空" }
    }
    // ------------------- 核心位操作工具 -------------------
    /**
     * 读取指定位数的无符号整数
     * @param bitCount 要读取的位数（1~32）
     */
    private fun readBits(bitCount: Int): Int? {
        require(bitCount in 1..32) { "bitCount 必须在1~32之间" }
        var remainingBits = bitCount
        var result = 0

        while (remainingBits > 0) {
            // 检查是否耗尽数据
            if (currentByteIndex >= payload.size) return null

            val currentByte = payload[currentByteIndex].toInt() and 0xFF
            val availableBits = 8 - startBitOffset
            val bitsToRead = minOf(availableBits, remainingBits)

            // 提取当前字节的指定位
            val mask = (1 shl availableBits) - 1
            val bits = (currentByte ushr (availableBits - bitsToRead)) and mask

            // 合并到结果
            result = (result shl bitsToRead) or bits
            remainingBits -= bitsToRead

            // 更新位置
            startBitOffset += bitsToRead
            if (startBitOffset >= 8) {
                startBitOffset -= 8
                currentByteIndex++
            }
        }
        return result
    }

    /**
     * 读取连续的n位无符号伪距（适用于RTCM/MSM消息）
     * @param count 要读取的伪距数量
     * @param n 要读取的n位
     * @return List<Int>（n位无符号，范围0~32767），或null（数据不足）
     */
    fun read_n_BitUintRanges(count: Int, n:Int): List<Int>? {
        if (count <= 0) return emptyList()
        val result = mutableListOf<Int>()
        val totalBitsNeeded = count * n
        val totalBytesNeeded = (totalBitsNeeded + startBitOffset + 7) / 8

        // 检查剩余数据是否足够
        if (currentByteIndex + totalBytesNeeded > payload.size) return null

        repeat(count) {
            val value = readBits(n) ?: return null
            result.add(value)
        }
        return result
    }

    /**
     * 读取连续的n位无符号伪距（适用于RTCM/MSM消息）
     * @param count 要读取的伪距数量
     * @param n 要读取的n位
     * @return List<Int>（n位无符号，范围0~32767），或null（数据不足）
     */
    fun read_n_BitIntRanges(count: Int, n:Int): List<Int>? {
        if (count <= 0) return emptyList()
        val result = mutableListOf<Int>()
        val totalBitsNeeded = count * n
        val totalBytesNeeded = (totalBitsNeeded + startBitOffset + 7) / 8

        // 检查剩余数据是否足够
        if (currentByteIndex + totalBytesNeeded > payload.size) return null

        repeat(count) {
            val value = readSignedBits(n) ?: return null
            result.add(value)
        }
        return result
    }
    
    

    fun read_n_Bit(n: Int): Int? {
        return readBits(n)
    }
    /**
     * 读取指定位数的有符号整数（二进制补码表示）
     */
    fun readSignedBits(bitCount: Int): Int? {
//        val unsigned = readBits(bitCount) ?: return null
//        // 如果最高位是1，表示负数
//        if (unsigned and (1 shl (bitCount - 1)) != 0) {
//            return unsigned - (1 shl bitCount) // 转换为负数
//        }
//        return unsigned
//        require(bitCount in 1..32) { "bitCount must be 1-32" }
//        val unsigned = readBits(bitCount) ?: return null
//        return if (bitCount == 32) {
//            unsigned  // 直接返回，因为Int本身就是32位有符号
//        } else if (unsigned and (1 shl (bitCount - 1)) != 0) {
//            unsigned or (Int.MIN_VALUE ushr (31 - bitCount))  // 安全符号扩展
//        } else {
//            unsigned
//        }
//        }
        require(bitCount in 1..32) { "bitCount must be 1-32" }
        val unsigned = readBits(bitCount) ?: return null
        return if (bitCount == 32) {
            unsigned // Int本身就是32位有符号
        } else if (unsigned and (1 shl (bitCount - 1)) != 0) {
            unsigned or ((-1) shl bitCount) // 符号扩展
        } else {
            unsigned
        }
    }}
