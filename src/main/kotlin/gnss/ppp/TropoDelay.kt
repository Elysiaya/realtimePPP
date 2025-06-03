package org.example.gnss.ppp

import kotlin.math.*

/**
 * GMF（Global Mapping Function）模型计算映射函数
 * @param elevation 仰角（弧度）
 * @param latitude 接收机纬度（弧度）
 * @param height 接收机高度（米）
 * @param doy 年积日（1-366）
 * @return Pair<干映射函数, 湿映射函数>
 */
fun GmfMappingFunction(
    elevation: Double,
//    latitude: Double,
//    height: Double,
//    doy: Int
): Pair<Double, Double> {
    // GMF系数（简化版，实际需查表或调用外部系数）
    val aht = 2.53e-5
    val bht = 5.49e-3
    val cht = 1.14e-3

    // 干分量映射函数
    val mh = (1 + aht / (1 + bht / (1 + cht))) /
            (sin(elevation) + aht / (sin(elevation) + bht / (sin(elevation) + cht)))

    // 湿分量映射函数（简化处理，实际GMF更复杂）
    val mw = 1 / (sin(elevation) + 0.017)

    return Pair(mh, mw)
}

///**
// * 计算对流层斜路径延迟
// * @param ztd 天顶对流层延迟（米）
// * @param elevation 仰角（度）
// * @param latitude 纬度（度）
// * @param height 高度（米）
// * @param doy 年积日
// * @return 斜路径延迟（米）
// */
//fun calculateTropoDelay(
//    ztd: Double,
//    elevation: Double,
//    latitude: Double,
//    height: Double,
//    doy: Int
//): Double {
//    val elevRad = Math.toRadians(elevation)
//    val latRad = Math.toRadians(latitude)
//    val (mh, mw) = calculateGmfMappingFunction(elevRad, latRad, height, doy)
//
//    // 假设干湿延迟比例（实际需根据气象数据或模型估算）
//    val zhd = ztd * 0.9  // 天顶干延迟
//    val zwd = ztd * 0.1   // 天顶湿延迟
//
//    return zhd * mh + zwd * mw
//}

//// 使用示例
//fun main() {
//    val ztd = 2.5  // 天顶对流层延迟（米）
//    val elevation = 30.0  // 仰角（度）
//    val latitude = 40.0   // 接收机纬度（度）
//    val height = 100.0    // 高度（米）
//    val doy = 150         // 年积日
//
//    val tropoDelay = calculateTropoDelay(ztd, elevation, latitude, height, doy)
//    println("斜路径对流层延迟: %.4f 米".format(tropoDelay))
//}
