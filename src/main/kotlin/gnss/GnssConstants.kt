package org.example.gnss

/**
 * GNSS 相关物理常量及配置
 */
object GnssConstants {
    // 物理常量
    const val C = 299792458.0      // 光速 (m/s)
    const val EARTH_GM = 3.986004418e14         // 地球引力常数 (m^3/s^2)
    const val RELATIVITY_F = -4.442807633e-10   // 相对论修正系数 (s/m^0.5)

    // GPS频率 (Hz)
    const val F1 = 1575.42e6  // L1: 1575.42 MHz
    const val F2 = 1227.60e6  // L2: 1227.60 MHz
    const val F5 = 1176.45e6  // L5: 1176.45 MHz
}