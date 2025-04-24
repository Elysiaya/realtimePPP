package org.example.parser

import kotlin.math.pow
import kotlin.math.*

/**
 * GPS 广播星历数据类
 * 参考：IS-GPS-200 标准（GPS接口控制文档）
 */
data class GpsEphemeris(
    // ---------- 卫星和参考时间信息 ----------
    val prn: Int,                // 卫星PRN号（1-32）
    val toc: Int,             // 星历参考时间（秒，GPS周内秒）
    val week: Int,               // GPS周数（从1980-01-06起）

    // ---------- 开普勒轨道参数 ----------
    val sqrtA: Double,           // 轨道长半轴平方根 (√m)
    val e: Double,               // 轨道偏心率
    val i0: Double,              // 参考时刻的轨道倾角 (rad)
    val omega0: Double,          // 参考时刻的升交点赤经 (rad)
    val omega: Double,           // 近地点角距 (rad)
    val m0: Double,              // 参考时刻的平近点角 (rad)

    // ---------- 摄动校正参数 ----------
    val deltaN: Double,          // 平均角速度校正值 (rad/s)
    val idot: Double,            // 轨道倾角变化率 (rad/s)
    val omegaDot: Double,        // 升交点赤经变化率 (rad/s)

    // ---------- 谐波摄动校正 ----------
    val cuc: Double,             // 纬度幅角余弦调和项 (rad)
    val cus: Double,             // 纬度幅角正弦调和项 (rad)
    val crc: Double,             // 轨道半径余弦调和项 (m)
    val crs: Double,             // 轨道半径正弦调和项 (m)
    val cic: Double,             // 轨道倾角余弦调和项 (rad)
    val cis: Double,             // 轨道倾角正弦调和项 (rad)

    // ---------- 时钟校正参数 ----------
    val tgd: Double,             // 群波延迟 (s)
    val af0: Double,             // 时钟偏差 (s)
    val af1: Double,             // 时钟漂移 (s/s)
    val af2: Double              // 时钟漂移率 (s/s²)
) {
    // ---------- 计算辅助常量 ----------
    companion object {
        const val MU = 3.986005e14     // 地球引力常数 (m³/s²)
        const val OMEGA_E = 7.2921151467e-5  // 地球自转角速度 (rad/s)
    }

    // ---------- 计算卫星位置的方法 ----------
    fun calculateSatellitePosition(time: Double): Triple<Double, Double, Double> {
        // 1. 计算相对于参考时间的时间差
        val tk = time - toc

        // 2. 计算校正后的平均角速度
        val n0 = sqrt(MU / sqrtA.pow(6))
        val n = n0 + deltaN

        // 3. 计算平近点角
        val mk = m0 + n * tk

        // 4. 解开普勒方程求偏近点角（迭代法）
        var ek = mk
        repeat(5) {  // 通常3-5次迭代足够收敛
            ek = mk + e * sin(ek)
        }

        // 5. 计算真近点角
        val vk = atan2(
            sqrt(1 - e.pow(2)) * sin(ek),
            cos(ek) - e
        )

        // 6. 计算纬度幅角
        val phik = vk + omega

        // 7. 计算摄动校正
        val deltaUk = cus * sin(2 * phik) + cuc * cos(2 * phik)
        val deltaRk = crs * sin(2 * phik) + crc * cos(2 * phik)
        val deltaIk = cis * sin(2 * phik) + cic * cos(2 * phik)

        // 8. 计算校正后的参数
        val uk = phik + deltaUk
        val rk = sqrtA.pow(2) * (1 - e * cos(ek)) + deltaRk
        val ik = i0 + deltaIk + idot * tk

        // 9. 计算在轨道平面内的位置
        val xkPrime = rk * cos(uk)
        val ykPrime = rk * sin(uk)

        // 10. 计算升交点经度
        val omegak = omega0 + (omegaDot - OMEGA_E) * tk - OMEGA_E * toc

        // 11. 计算地固坐标系下的位置
        val x = xkPrime * cos(omegak) - ykPrime * cos(ik) * sin(omegak)
        val y = xkPrime * sin(omegak) + ykPrime * cos(ik) * cos(omegak)
        val z = ykPrime * sin(ik)

        return Triple(x, y, z)
    }

    // ---------- 计算卫星时钟偏差 ----------
    fun calculateClockOffset(time: Double): Double {
        val dt = time - toc
        return af0 + af1 * dt + af2 * dt.pow(2) + tgd
    }
}
