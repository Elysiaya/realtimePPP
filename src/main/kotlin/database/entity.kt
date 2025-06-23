package org.example.database

import kotlinx.serialization.json.Json
import org.example.gnss.GnssConstants
import org.example.gnss.GnssConstants.F1
import org.example.gnss.GnssConstants.F2
import org.example.parser.SignalType
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import kotlin.math.pow

object SatelliteEphemeris : Table("satellite_ephemeris") {
    val id: Column<Int> = integer("id").autoIncrement()
    val prn = integer("prn")
    val toc = long("toc")
    val week = integer("week")
    val sqrtA = double("sqrtA")
    val e = double("e")
    val i0 = double("i0")
    val omega0 = double("omega0")
    val omega = double("omega")
    val m0 = double("m0")
    val deltaN = double("deltaN")
    val idot = double("idot")
    val omegaDot = double("omegaDot")
    val cuc = double("cuc")
    val cus = double("cus")
    val crc = double("crc")
    val crs = double("crs")
    val cic = double("cic")
    val cis = double("cis")
    val tgd = double("tgd")
    val af0 = double("af0")
    val af1 = double("af1")
    val af2 = double("af2")
    val satelliteClockBias = double("satelliteClockBias")

    override val primaryKey = PrimaryKey(id, name = "PK_User_ID")
}
object SignalObservation : Table("signal_observation") {
    val id = integer("id").autoIncrement()
    val prn = integer("prn").references(SatelliteEphemeris.prn).default(0)
    val tow = long("tow")
    val signalType = varchar("signal_type", 50)
    val pseudorange = varchar("pseudorange",200)
    val phaserange = varchar("phaserange",200)
//    val wavelengthIf = double("wavelength_if")
    val cnr = varchar("cnr",100)
    val lockTime = varchar("lock_time",100)
    val halfCycleAmbiguity = varchar("half_cycle_ambiguity",100)

    override val primaryKey = PrimaryKey(id, name = "PK_User_ID2")

    // 创建索引
    init {
        index(false, prn, tow)
    }
}

data class SignalObservationData(
    val id: Int = 0,  // 自动递增，创建时通常不需要指定
    val prn: Int = 0,
    val tow: Long,
    val signalTypes: List<SignalType?>,  // 解码自 signalType 列的 JSON 字符串
    val pseudoranges: List<Double>,  // 解码自 pseudorange 列的 JSON 字符串
    val phaseranges: List<Double>,  // 解码自 phaserange 列的 JSON 字符串
//    val wavelengthIf: Double,
    val cnrs: List<Double>,  // 解码自 cnr 列的 JSON 字符串
    val lockTimes: List<Double>,  // 解码自 lockTime 列的 JSON 字符串
    val halfCycleAmbiguities: List<Int>  // 解码自 halfCycleAmbiguity 列的 JSON 字符串
) {
    companion object {
        // 从数据库行创建 SignalObservationData 的扩展函数
        fun fromResultRow(row: ResultRow): SignalObservationData {
            return SignalObservationData(
                id = row[SignalObservation.id],
                prn = row[SignalObservation.prn],
                tow = row[SignalObservation.tow],
                signalTypes = Json.decodeFromString(row[SignalObservation.signalType]),
                pseudoranges = Json.decodeFromString(row[SignalObservation.pseudorange]),
                phaseranges = Json.decodeFromString(row[SignalObservation.phaserange]),
//                wavelengthIf = row[SignalObservation.wavelengthIf],
                cnrs = Json.decodeFromString(row[SignalObservation.cnr]),
                lockTimes = Json.decodeFromString(row[SignalObservation.lockTime]),
                halfCycleAmbiguities = Json.decodeFromString(row[SignalObservation.halfCycleAmbiguity])
            )
        }
    }
    fun getObsfromsignal(signalType: SignalType): Pair<Double, Double> {
        require(signalTypes.contains(signalType)){"$signalType does not exist"}
        val i = signalTypes.indexOf(signalType)
        return Pair(pseudoranges[i],phaseranges[i])
    }
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
            else -> pseudoranges[0]
        }
    }
    //获取无电离层组合观测值,载波相位
    fun IF_combination2(): Double{
        return when{
            SignalType.L1C in signalTypes && SignalType.L2L in signalTypes -> IF(getObsfromsignal(SignalType.L1C).second, getObsfromsignal(SignalType.L2L).second)
            SignalType.L1W in signalTypes && SignalType.L2W in signalTypes -> IF(getObsfromsignal(SignalType.L1W).second, getObsfromsignal(SignalType.L2W).second)
            SignalType.L1C in signalTypes && SignalType.L2W in signalTypes -> IF(getObsfromsignal(SignalType.L1C).second, getObsfromsignal(SignalType.L2L).second)
            SignalType.L1C in signalTypes && SignalType.L2C in signalTypes -> IF(getObsfromsignal(SignalType.L1C).second, getObsfromsignal(SignalType.L2C).second)
            else -> phaseranges[0]
        }
    }
    /**
     * 计算无电离层组合（IF组合）的波长
     * @param f1 第一个频率（Hz）
     * @param f2 第二个频率（Hz）
     * @return IF组合波长（米）
     */
    fun calculateIfWavelength(f1: Double, f2: Double): Double {
        val a1 = f1*f1 - f2*f2
        val a2 = f1*f1*f1 - f2*f2*f2
        val s = GnssConstants.C * a1 / a2
        return s
    }
}