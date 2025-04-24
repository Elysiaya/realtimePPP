package org.example.gnss

import kotlinx.coroutines.*
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.InputStream

class RtcmStreamProcessor(private val inputStream: InputStream) {
    // 用于传递解析后的RTCM消息
    private val messageChannel = Channel<RawRtcmMessage>(Channel.BUFFERED)
    val messageFlow: Flow<RawRtcmMessage> = messageChannel.receiveAsFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // RTCM消息前导字节
    private companion object {
        const val RTCM_PREAMBLE = 0xD3
        const val HEADER_LENGTH = 6 // 3字节头 + 3字节CRC
    }

    init {
        scope.launch {
            try {
                processRtcmStream()
            } catch (e: Exception) {
                println("RTCM流处理错误: ${e.message}")
                messageChannel.close(e)
            }
        }
    }
    private suspend fun processRtcmStream() {
        val buffer = ByteArray(4096) // 更大的缓冲区
        var bufferPos = 0

        while (isActive) {
            // 读取新数据
            val bytesRead = withTimeoutOrNull(1000) {
                inputStream.read(buffer, bufferPos, buffer.size - bufferPos)
            } ?: continue

            if (bytesRead == -1) break // 流结束
            bufferPos += bytesRead

            // 循环处理缓冲区中所有完整消息
            var processedBytes = 0
            while (true) {
                // 查找消息头
                val messageStart:Int = findMessageStart(buffer, processedBytes, bufferPos)
                if (messageStart == -1) {
                    // 没有找到更多有效消息
                    break
                }

                // 检查是否有足够数据读取头
                if (bufferPos - messageStart < 3) break

                // 解析头
                val header = parseHeader(buffer, messageStart)

                // 检查是否有完整消息
                val totalMessageLength = 3 + header.length + 3 // 头+数据+CRC
                if (bufferPos - messageStart < totalMessageLength) break

                // 提取消息
                val messageEnd = messageStart + totalMessageLength
                val payload = buffer.copyOfRange(messageStart + 3, messageStart + 3 + header.length)
                val receivedCrc = (buffer[messageStart + 3 + header.length].toInt() and 0xFF shl 16) or
                        (buffer[messageStart + 3 + header.length + 1].toInt() and 0xFF shl 8) or
                        (buffer[messageStart + 3 + header.length + 2].toInt() and 0xFF)

                // 计算并验证CRC
                val crcValid = verifyCrc(buffer, messageStart, 3 + header.length, receivedCrc)

                // 发送消息
                messageChannel.send(
                    RawRtcmMessage(
                        header = header,
                        payload = payload,
                        crcValid = crcValid
                    )
                )

                processedBytes = messageEnd
            }

            // 移动未处理数据到缓冲区开头
            if (processedBytes > 0) {
                System.arraycopy(buffer, processedBytes, buffer, 0, bufferPos - processedBytes)
                bufferPos -= processedBytes
            } else if (bufferPos == buffer.size) {
                // 缓冲区满但没有找到有效消息，丢弃前半部分防止溢出
                System.arraycopy(buffer, buffer.size / 2, buffer, 0, bufferPos - buffer.size / 2)
                bufferPos -= buffer.size / 2
            }
        }

        messageChannel.close()
    }


    private fun findMessageStart(buffer: ByteArray, start: Int, end: Int): Int {
        for (i in start until end - 2) {
            if ((buffer[i].toInt() and 0xFF) == RTCM_PREAMBLE) {
                // 检查是否是有效的消息头开始
                if (i + 3 <= end) {
                    val header = parseHeader(buffer, i)
                    if (header.length in 1..1023) { // 合理的长度检查
                        return i
                    }
                }
            }
        }
        return -1
    }

    private fun parseHeader(buffer: ByteArray, start: Int): RtcmHeader {
//        这里是前3个字节，24个bit，属于消息头的内容，按照文档，前8bit为11010011(0xD3,211)
        val byte1 = buffer[start].toInt() and 0xFF
        val byte2 = buffer[start + 1].toInt() and 0xFF
        val byte3 = buffer[start + 2].toInt() and 0xFF

        // 解析消息类型和长度
        val reserved = byte1
        val messageType = ((buffer[start+3].toInt() and 0xFF) shl 4) or
                ((buffer[start+4].toInt() and 0xFF) shr 4)
        val length = ((buffer[start+1].toInt() and 0x03) shl 8) or
                (buffer[start+2].toInt() and 0xFF)

        return RtcmHeader(
            messageType = messageType,
            length = length,
            crc = 0 // CRC将在后面单独解析
        )
    }

    private fun verifyCrc(buffer: ByteArray, start: Int, length: Int, receivedCrc: Int): Boolean {
        // 简化的CRC校验示例，实际应使用RTCM标准CRC24Q算法
        // 这里只是示例，实际实现应使用正确的CRC算法
        var crc = 0
        for (i in start until start + length) {
            crc = crc xor (buffer[i].toInt() and 0xFF)
        }
//        return crc == receivedCrc
        return true
    }

    fun close() {
        scope.cancel()
        runBlocking { messageChannel.close() }
        inputStream.close()
    }
}
