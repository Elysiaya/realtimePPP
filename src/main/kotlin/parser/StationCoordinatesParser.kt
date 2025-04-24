package org.example.parser

import gnss.IRtcmMessage
import org.example.gnss.RtcmHeader
import org.example.gnss.RawRtcmMessage

class Coordinates(
    override val header: RtcmHeader,
    val messagetype: Int,
    val stationId: Int,
    val position: Triple<Double, Double, Double>, // X,Y,Z in meters
    val antenna_height: Double,
): IRtcmMessage {
    override fun toHumanReadable(): String {
        return """
            基站坐标: ID=${stationId},
            X=${position.first},
            Y=${position.second},
            Z=${position.third}
        """.trimIndent()
    }
}

class StationCoordinatesParser: IRtcmMessageParser {
    override fun parse(rawRtcmMessage: RawRtcmMessage): IRtcmMessage {
        val header = rawRtcmMessage.header
        val payload = rawRtcmMessage.payload
        val x = _38bitLong(payload,4)
        val y = _38bitLong(payload,9)
        val z = _38bitLong(payload,14)

        return Coordinates(
            header=header,
            messagetype = ((payload[0].toInt() and 0xFF) shl  4) or (payload[1].toInt() and 0xF0 shr 4),
            stationId = (payload[1].toInt() and 0x0f shl 8) or (payload[2].toInt() and 0xff),
            position = Triple(
                x * 0.0001, // m
                 y * 0.0001,
                z * 0.0001
            ),
            antenna_height = ((payload[19].toInt() and 0xFF shl 8) or (payload[20].toInt() and 0xFF))*0.0001
        )
    }

    fun showMessage(parsed: Coordinates) {
        println("基站坐标: ID=${parsed.stationId}, X=${parsed.position.first}, Y=${parsed.position.second}, Z=${parsed.position.third}")
    }

    fun _38bitLong(payload: ByteArray,start:Int): Long {
        val raw38Bits = (
                (payload[start].toLong() and 0x3F shl 32) or  // 取第4字节低6位（bits 32-37）
                        (payload[start+1].toLong() and 0xFF shl 24) or   // bits 24-31
                        (payload[start+2].toLong() and 0xFF shl 16) or   // bits 16-23
                        (payload[start+3].toLong() and 0xFF shl 8) or    // bits 8-15
                        (payload[start+4].toLong() and 0xFF)             // bits 0-7
                ) and 0x3FFFFFFFFFL  // 确保不超过38位
        // Step 2: 处理有符号数（符号位在第37位）
        return if(raw38Bits and 0x2000000000 != 0L) { // 检查符号位
            raw38Bits or (-0x1L shl 38)  // 负数符号扩展
        } else {
            raw38Bits  // 正数直接使用
        }
    }
}
