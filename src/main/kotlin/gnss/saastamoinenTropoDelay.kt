package org.example.gnss

import kotlin.math.*

/**
 * 计算 Saastamoinen 模型的对流层延迟（天顶方向，ZTD）
 * @param elevationDeg 卫星高度角（度，0-90）
 * @param pressure 测站气压（hPa，默认 1013.25）
 * @param temperature 测站温度（K，默认 291.15）
 * @param humidity 相对湿度（%，0-100，默认 50）
 * @param height 测站高程（米，默认 0）
 * @return 对流层延迟（米）
 */
fun saastamoinenTropoDelay(
    elevationDeg: Double,
    pressure: Double = 1013.25,
    temperature: Double = 291.15,
    humidity: Double = 50.0,
    height: Double = 0.0
): Double {
    // 将高度角转换为弧度
    val z = (90.0 - elevationDeg).toRadians()

    // 计算水汽压（e），使用 Magnus 公式近似
    val es = 6.112 * exp((17.62 * (temperature - 273.15)) / (temperature - 30.03)) // 饱和水汽压（hPa）
    val e = (humidity / 100.0) * es

    // Saastamoinen 模型参数
    val B = when {
        height <= 0.0 -> 0.0
        else -> 0.002277 * height / cos(z)
    }

    // 天顶对流层延迟（ZTD）
    val ztd = (0.002277 / cos(z)) * (pressure + (1255.0 / temperature + 0.05) * e - B * tan(z).pow(2))

    return ztd
}

// 扩展函数：角度转弧度
fun Double.toRadians() = this * PI / 180.0