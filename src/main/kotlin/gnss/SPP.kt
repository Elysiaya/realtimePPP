package org.example.gnss

import kotlinx.serialization.Serializable
import org.example.database.SignalObservationData
import org.example.gnss.GnssConstants.C
import org.example.parser.GpsEphemeris
import org.example.parser.Msm7GPSobs
import org.jetbrains.kotlinx.multik.api.identity
import org.jetbrains.kotlinx.multik.api.linalg.dot
import org.jetbrains.kotlinx.multik.api.linalg.inv
import org.jetbrains.kotlinx.multik.api.mk
import org.jetbrains.kotlinx.multik.api.ndarray
import org.jetbrains.kotlinx.multik.ndarray.data.get
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SPP() {

    //标准SPP算法，计算大致位置
    public fun SPP(gnssdata: List<SatelliteData>): PositionResult {
        //真值，测试用
        val X = -0.405205293920631E+07

        val Y = 0.421283594860667E+07

        val Z = -.254510429759719E+07


//        val X0 = mutableListOf(X, Y, Z, 0.0)
        val X0 = mutableListOf(1.0, 1.0, 1.0, 0.0)

        for (i in 0..20) {

            val r = computer(gnssdata, X0.toTypedArray(), i, 15.0)

            require(r.first.size > 3) { "至少4颗卫星才能定位" }

            val A = mk.ndarray(r.first.toTypedArray())
            val L = mk.ndarray(r.second.toDoubleArray())

            val P = mk.identity<Double>(A.shape[0])

            val Xi = mk.linalg.inv(A.transpose() dot P dot A) dot (A.transpose() dot P dot L)


            val deltaNorm = sqrt(Xi[0].square() + Xi[1].square() + Xi[2].square() + Xi[3].square())

            if (deltaNorm < 1e-4) {  // 更严格的阈值
                println("收敛条件满足")
                println("在高度角范围内的卫星有${A.shape[0]}颗")
                val E = ecefToGeodetic(X,Y,Z)
                val T = ecefToGeodetic(X0[0],X0[1],X0[2])

                println("接收机坐标(ECEF):${X0.take(3)}")
                println("接收机坐标(BLH):${T}")
                println("接收机钟差为:${X0[3] / C}")

                println("DelateX = ${X0[0] - X}")
                println("DelateY = ${X0[1] - Y}")
                println("DelateZ = ${X0[2] - Z}")

                println("DelateB = ${T[0] - E[0]}")
                println("DelateL = ${T[1] - E[1]}")
                println("DelateH = ${T[2] - E[2]}")

                break
            } else {
                for (i in X0.indices) {
                    X0[i] = X0[i] + Xi[i]
                }
            }
        }
        return PositionResult.Success(
            x = X0[0],
            y = X0[1],
            z = X0[2],
            accuracy = 0.0,
            clockBias = X0[3]
        )


    }

    private fun computer(
        gnssdata: List<SatelliteData>,
        X0: Array<Double>,
        iter: Int,
        cutoff_angle: Double
    ): Pair<MutableList<DoubleArray>, MutableList<Double>> {

        val A = mutableListOf<DoubleArray>()
        val L = mutableListOf<Double>()

        for (data in gnssdata) {
            val observations_time = data.obs.tow.toDouble() - X0[3] / C
            val o = data.obs.IF_combination()

            var satellite_position: List<Double> =
                data.ephemeris.calculateSatellitePosition(observations_time, o)


            //计算卫星高度角
            var elevation = 15.0
            if (iter>3){
                elevation = calculateElevation(X0.take(3), satellite_position) // 需实现高度角计算
                if (elevation < cutoff_angle || data.obs.cnrs.first()<36) continue
            }

            //计算卫星和测站的几何距离
            val Rsr =
                sqrt((satellite_position[0] - X0[0]).square() + (satellite_position[1] - X0[1]).square() + (satellite_position[2] - X0[2]).square())

            val b0si = (X0[0] - satellite_position[0]) / Rsr
            val b1si = (X0[1] - satellite_position[1]) / Rsr
            val b2si = (X0[2] - satellite_position[2]) / Rsr
            val b3si = 1.0

            val diono = 0  // 电离层延迟改正量，采用无电离层伪距观测组合值时此项为0
            val D_RTCM = 0  // 对伪距的差分改证
            val D_troposphere = saastamoinenTropoDelay(elevation)
            val dts = data.ephemeris.satelliteClockBias
            val dtprel = 0//相对论效应改正

            val predictedRange = Rsr + X0[3] - C * dts + D_troposphere + diono
            val l = o - predictedRange

            A.add(doubleArrayOf(b0si, b1si, b2si, b3si))
            L.add(l)
        }
        return Pair(A, L)
    }

    fun calculateElevation(
        receiverPos: List<Double>,  // ECEF [x, y, z]
        satellitePos: List<Double>  // ECEF [x, y, z]
    ): Double {
        require(receiverPos.size == 3 && satellitePos.size == 3) { "坐标必须是3维" }

        // 1. 计算接收机的大地坐标（经纬度）
        val (lat, lon, _) = ecefToGeodetic(receiverPos[0], receiverPos[1], receiverPos[2])

        // 2. 计算接收机到卫星的向量（ECEF）
        val dx = satellitePos[0] - receiverPos[0]
        val dy = satellitePos[1] - receiverPos[1]
        val dz = satellitePos[2] - receiverPos[2]

        // 3. 计算 ENU 转换矩阵
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val sinLon = sin(lon)
        val cosLon = cos(lon)

        // 4. 转换到 ENU 坐标系
        val e = -sinLon * dx + cosLon * dy  // 东分量
        val n = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz  // 北分量
        val u = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz  // 天顶分量

        // 5. 计算高度角（atan2 更稳定）
        val elevationRad = atan2(u, sqrt(e * e + n * n))
        return Math.toDegrees(elevationRad)
    }

    fun ecefToGeodetic(x: Double, y: Double, z: Double): List<Double> {
        val a = 6378137.0 // WGS84 椭球长半轴
        val f = 1.0 / 298.257223563 // WGS84 扁率
        val b = a * (1 - f) // 短半轴
        val e = sqrt(1 - (b * b) / (a * a)) // 第一偏心率

        val lon = atan2(y, x) // 经度直接计算

        // 纬度迭代计算（初始值设为反正切近似）
        var lat = atan2(z, sqrt(x * x + y * y))
        var prevLat: Double
        var N: Double
        var deltaZ: Double
        val epsilon = 1e-12 // 收敛阈值

        do {
            prevLat = lat
            N = a / sqrt(1 - e * e * sin(lat) * sin(lat))
            deltaZ = z - N * (1 - e * e) * sin(lat)
            lat = atan2(z + N * e * e * sin(lat), sqrt(x * x + y * y))
        } while (abs(lat - prevLat) > epsilon)

        // 计算高度
        N = a / sqrt(1 - e * e * sin(lat) * sin(lat))
        val height = sqrt(x * x + y * y) / cos(lat) - N

        return listOf(lat, lon,height)
    }

    fun Double.square() = this * this
    fun Double.radToDeg() = this * 180.0 / PI
}



data class SatelliteData(
    val prn: Int,
    val obs: SignalObservationData,
    val ephemeris: GpsEphemeris
)


//密封类，用于表示几种特定的类型，它的所有子类必须再编译期确定，常用于表示固定的几种可能状态
sealed class PositionResult {
    data class Success(
        val x: Double,
        val y: Double,
        val z: Double,
        val accuracy: Double,
        val clockBias: Double //单位，米
    ) : PositionResult()

    data class Error(val message: String) : PositionResult()
}