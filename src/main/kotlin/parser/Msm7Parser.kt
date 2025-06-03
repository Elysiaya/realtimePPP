package org.example.parser

import gnss.IRtcmMessage
import kotlinx.serialization.Serializable
import org.example.gnss.GnssConstants.C
import org.example.gnss.RtcmHeader
import org.example.gnss.RawRtcmMessage
import org.example.tools.AlignedBitReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class Msm7Parser : IRtcmMessageParser {
    val satellites:List<Msm7GPSobs> = mutableListOf<Msm7GPSobs>()
    override fun parse(rawRtcmMessage: RawRtcmMessage): IRtcmMessage {
        val header = rawRtcmMessage.header
        val payload = rawRtcmMessage.payload
        val buffer = ByteBuffer.wrap(payload).apply {
            order(ByteOrder.BIG_ENDIAN) // RTCM 使用大端序
        }

        // 解析头部字段
        val messagetype = ((payload[0].toInt() and 0xFF) shl  4) or (payload[1].toInt() and 0xF0 shr 4)
        //测站ID
        val stationId = ((payload[1].toInt() and 0x0F) shl  8) or (payload[2].toInt() and 0xFF)
        //GNSS历元时间，单位秒
        val tow = ((payload[3].toInt() and 0xFF shl 22) or
                (payload[4].toInt() and 0xFF shl 14) or
                (payload[5].toInt() and 0xFF shl 6) or
                (payload[6].toInt() and 0xFF shr 2))/1000
        //多信号标志，判断是否为当前时刻最后一条消息
        val multipleMessageBit = (payload[6].toInt() and 0x02) == 0x02
//        println("payload[0] (binary): ${(payload[0].toInt() and 0xFF).toString(2).padStart(8, '0')}")
//        println("payload[1] (binary): ${(payload[1].toInt() and 0xFF).toString(2).padStart(8, '0')}")
//        println("payload[2] (binary): ${(payload[2].toInt() and 0xFF).toString(2).padStart(8, '0')}")
//        println("payload[3] (binary): ${(payload[3].toInt() and 0xFF).toString(2).padStart(8, '0')}")
//        println("payload[4] (binary): ${(payload[4].toInt() and 0xFF).toString(2).padStart(8, '0')}")
//        println("payload[5] (binary): ${(payload[5].toInt() and 0xFF).toString(2).padStart(8, '0')}")
//        println("payload[6] (binary): ${(payload[6].toInt() and 0xFF).toString(2).padStart(8, '0')}")
//        println()

        // 解析卫星数据
        val satellites= parseMsm7Content(payload,tow)

        return Msm7Message(
            header = header,
            stationId = stationId,
            tow = tow,
            multipleMessageBit = multipleMessageBit,
            satellites = satellites,
        )
    }

    private fun parseMsm7Content(payload: ByteArray,tow:Int): List<Msm7GPSobs>{
        // 1. 解析卫星掩码（按位表示存在的卫星）
        val satelliteMask = parseBitMask(payload,9, MAX_SATELLITES)
        val satelliteCount = satelliteMask.count { it }

        // 2. 解析信号掩码（按位表示存在的信号类型）
        val signalMask = parseBitMask(payload,17, MAX_SIGNALS)
        val signalCount = signalMask.count { it }

        //获取GNSS Cell Mask
        val x = satelliteCount * signalCount
        val gnssCellMask = parseBitMask(payload,21,x)

        // 创建读取器，从 payload[21] 的 bit 1（低7位）开始
        val reader = AlignedBitReader(
            payload = payload.drop((169+x)/8).toByteArray(),
            startBitOffset = (169+x) % 8
        )

        // 3. 解析卫星数据
        val satellites = parseSatellites(reader, satelliteMask,signalMask,gnssCellMask,tow)

        return satellites
    }


    private fun parseSatellites(reader: AlignedBitReader, satelliteMask: List<Boolean>,signalMask:List<Boolean>,gnssCellMask:List<Boolean>,tow:Int): List<Msm7GPSobs> {
        //[21]

        // 读取伪距（每个卫星对应一个8位粗略伪距和10位精细伪距）
        val satelliteCount = satelliteMask.count { it }
        val signalTypeCount = signalMask.count { it }
        val ncell = gnssCellMask.count { it }

        //The number of integer milliseconds in GNSS Satellite  rough ranges
        val ranges1 = reader.read_n_BitUintRanges(satelliteCount,8) ?: return emptyList()
        val extendedSatelliteInformation = reader.read_n_BitUintRanges(satelliteCount,4)?: return emptyList()
        //GNSS Satellite rough ranges modulo 1 millisecond
        val ranges2 = reader.read_n_BitUintRanges(satelliteCount,10) ?: return emptyList()
        //GNSS Satellite rough PhaseRangeRates
        val  roughPhaseRangeRates = reader.read_n_BitUintRanges(satelliteCount,14) ?: return emptyList()


        val signalslist = signalMask.withIndex()
            .filter { it.value }
            .map {it -> SignalType.fromInt(it.index + 1) }
            .toList()



        val finePseudorange = reader.read_n_BitIntRanges(ncell,20) ?: return emptyList()
        val phase = reader.read_n_BitIntRanges(ncell,24) ?: return emptyList()
        val GNSSPhaserangeLockTimeIndicator = reader.read_n_BitUintRanges(ncell,10) ?: return emptyList()
        //HalfcycleambiguityIndicator暂时有些问题
        val HalfcycleambiguityIndicator = reader.read_n_BitUintRanges(ncell,1) ?: return emptyList()
        val CNRs = reader.read_n_BitUintRanges(ncell,10) ?: return emptyList()
        val finePhaseRangeRates = reader.read_n_BitUintRanges(ncell,15) ?: return emptyList()

        val ss = mutableListOf<Msm7GPSobs>()

//        指示第几颗卫星
        var idx = 0
        //指示信号观测量的索引
        var idx2 = 0
        for ((index,item) in satelliteMask.withIndex().filter { it.value })
        {
            val satellitesignalslist = signalslist.filter {
                gnssCellMask.slice(idx*signalslist.size until (idx+1)*signalslist.size)[signalslist.indexOf(it)]
            }
            val m = Msm7GPSobs(
                prn = index+1,
                nms = ranges1[idx],//单位毫秒
                roughRange = ranges2[idx],//小数部分
                signalTypes = satellitesignalslist,
                finePseudorange = finePseudorange.slice(idx2 until idx2+satellitesignalslist.size),
                finePhaserange = phase.slice(idx2 until idx2+satellitesignalslist.size),
                GNSSPhaserangeLockTimeIndicator = GNSSPhaserangeLockTimeIndicator.slice(idx2 until idx2+satellitesignalslist.size),
                HalfcycleambiguityIndicator = HalfcycleambiguityIndicator.slice(idx2 until idx2+satellitesignalslist.size),
                CNRs = CNRs.slice(idx2 until idx2+satellitesignalslist.size).map{it * 2.0.pow(-4)},
                tow = tow
            )
            ss.add(m)
            idx++
            idx2+=satellitesignalslist.size
        }

        return ss.toList()
    }

    private fun parseBitMask(byteArray: ByteArray, startByteIndex:Int, maxBit:Int): List<Boolean> {
        require(byteArray.size >= 17) { "byteArray 长度不足，至少需要 17 字节" }

        val bitList = mutableListOf<Boolean>()
//        val startByteIndex = 9  // 从 byteArray[9] 开始
        val startBitOffset = 1  // 低7位 = 跳过最高1位（bitOffset=0是最高位）

        // 遍历64位（8字节）
        for (i in 0 until maxBit) {
            // 计算当前字节和位偏移
            val currentByteIndex = startByteIndex + (i + startBitOffset) / 8
            val currentBitInByte = (i + startBitOffset) % 8

            // 获取当前字节
            val currentByte = byteArray[currentByteIndex]

            // 检查当前位是否为1（Kotlin 的位操作需显式调用 toInt()）
            val isBitSet = ((currentByte.toInt() shr (7 - currentBitInByte)) and 0x01) == 1
            bitList.add(isBitSet)
        }

        return bitList
    }

    companion object {
        private const val MAX_SATELLITES = 64
        private const val MAX_SIGNALS = 32
    }
}

// ------------------ 数据模型 ------------------
@Serializable
data class Msm7GPSobs(
    val prn: Int,                      // 卫星PRN号
    val signalTypes: List<SignalType?>,
    val nms: Int,//粗略伪距整毫秒
    val roughRange: Int,//粗略伪距，毫秒内，该数除以1024为毫秒数字
    val finePseudorange: List<Int>,       //精细伪距，该数除以2e-29
    val finePhaserange: List<Int>, //精细载波相位
    val GNSSPhaserangeLockTimeIndicator: List<Int>,//GNSS 载波相位 锁定时间标志
    val HalfcycleambiguityIndicator: List<Int>,//半周模糊度标志
    val CNRs: List<Double>,//GNSS 信号载噪比
    var tow: Int,
){

    companion object {
        // GPS频率 (Hz)
        const val F1 = 1575.42  // L1
        const val F2 = 1227.60  // L2
        const val F5 = 1176.45  // L5
    }
    val pseudorange:List<Double>
    val phaserange:List<Double>
    val wavelengthIF:Double
    init {
        pseudorange = finePseudorange.map { it->
            C/1000 * (nms + roughRange/1024.0 + it * 2.0.pow(-29))
        }
        phaserange = finePhaserange.map { it ->
            C/1000 * (nms + roughRange/1024.0 + it * 2.0.pow(-31))
        }
        wavelengthIF = calculateIfWavelength(F1,F2)
    }

    fun getObsfromsignal(signalType: SignalType): Pair<Double, Double> {
        require(signalTypes.contains(signalType)){"$signalType does not exist"}
        val i = signalTypes.indexOf(signalType)
//        return pseudorange[i]
        return Pair(pseudorange[i],phaserange[i])
    }

    /**
     * 无电离层组合（Ionosphere-Free, IF）
     *
     * @param o1 L1观测值
     * @param o2 L2观测值
     * @return
     */
    fun IF(o1: Double, o2: Double):Double{
        //目前只计算L1和L2
        return (F1.pow(2) * o1 - F2.pow(2) * o2)/(F1.pow(2)-F2.pow(2))
    }
    //获取无电离层组合观测值
    fun IF_combination(): Double{
        return when{
            SignalType.L1C in signalTypes && SignalType.L2L in signalTypes -> IF(getObsfromsignal(SignalType.L1C).first, getObsfromsignal(SignalType.L2L).first)
            SignalType.L1W in signalTypes && SignalType.L2W in signalTypes -> IF(getObsfromsignal(SignalType.L1W).first, getObsfromsignal(SignalType.L2W).first)
            SignalType.L1C in signalTypes && SignalType.L2W in signalTypes -> IF(getObsfromsignal(SignalType.L1C).first, getObsfromsignal(SignalType.L2L).first)
            SignalType.L1C in signalTypes && SignalType.L2C in signalTypes -> IF(getObsfromsignal(SignalType.L1C).first, getObsfromsignal(SignalType.L2C).first)
            else -> pseudorange[0]
        }
    }
    //获取无电离层组合观测值,载波相位
    fun IF_combination2(): Double{
        return when{
            SignalType.L1C in signalTypes && SignalType.L2L in signalTypes -> IF(getObsfromsignal(SignalType.L1C).second, getObsfromsignal(SignalType.L2L).second)
            SignalType.L1W in signalTypes && SignalType.L2W in signalTypes -> IF(getObsfromsignal(SignalType.L1W).second, getObsfromsignal(SignalType.L2W).second)
            SignalType.L1C in signalTypes && SignalType.L2W in signalTypes -> IF(getObsfromsignal(SignalType.L1C).second, getObsfromsignal(SignalType.L2L).second)
            SignalType.L1C in signalTypes && SignalType.L2C in signalTypes -> IF(getObsfromsignal(SignalType.L1C).second, getObsfromsignal(SignalType.L2C).second)
            else -> phaserange[0]
        }
    }
    /**
     * 计算无电离层组合（IF组合）的波长
     * @param f1 第一个频率（Hz）
     * @param f2 第二个频率（Hz）
     * @return IF组合波长（米）
     */
    fun calculateIfWavelength(f1: Double, f2: Double): Double {
        require(f1 > 0 && f2 > 0) { "频率必须为正数" }
        val alpha = (f1 * f1) / (f1 * f1 - f2 * f2)
        val beta = (f2 * f2) / (f1 * f1 - f2 * f2)
        val fIf = alpha * f1 - beta * f2
        return C / fIf
    }
}

enum class SignalType(val code: Int) {
    // 根据RTCM标准扩展(DF395)
    L1C(2), L1P(3),L1W(4),
    L2C(8), L2P(9),L2W(10),
    L2S(15),L2L(16),L2X(17),
    L5I(22),L5Q(23),L5X(24),
    L1S(30),L1L(31),L1X(32),
    UNKNOWN(-1);

    companion object {
        fun fromInt(code: Int) = entries.firstOrNull { it.code == code }?:UNKNOWN
    }
}

data class Msm7Message(
    override val header: RtcmHeader,
    val stationId: Int,
    val tow: Int,
    val multipleMessageBit: Boolean,//多信号标志，当其为true时表示后面还有信息
    val satellites: List<Msm7GPSobs>,
) : IRtcmMessage {
    override fun toHumanReadable() = """
        MSM7 Message (Type=${header.messageType}):
        Station ID: $stationId
        Satellites: ${satellites.size}
        ${satellites.take(3).joinToString("\n") { " - PRN ${it.prn}" }}

    """.trimIndent()}