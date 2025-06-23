package org.example.parser

import kotlinx.serialization.Serializable
import org.example.database.SignalObservationData
import org.example.gnss.GnssConstants
import kotlin.math.pow


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
    fun toSignalObservationData(): SignalObservationData {
        return SignalObservationData(
            prn = prn,
            tow = tow.toLong(),
            signalTypes = signalTypes,
            pseudoranges = finePseudorange.map { it->
                GnssConstants.C /1000 * (nms + roughRange/1024.0 + it * 2.0.pow(-29))
            },
            phaseranges = finePhaserange.map { it ->
                GnssConstants.C /1000 * (nms + roughRange/1024.0 + it * 2.0.pow(-31))
            },
            cnrs = CNRs,
            lockTimes = GNSSPhaserangeLockTimeIndicator.map { it.toDouble() },
            halfCycleAmbiguities = HalfcycleambiguityIndicator,
        )
    }
}