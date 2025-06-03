package org.example.gnss.ppp

import kotlinx.serialization.json.Json
import org.example.gnss.GnssConstants.C
import org.example.gnss.PositionResult
import org.example.gnss.SPP
import org.example.gnss.SatelliteData
import java.io.File
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.component3
import kotlin.collections.mutableListOf
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.sin

/**
 * PPP定位核心代码
 *
 */
fun PPP(){
    val epochs = getdata()
    val p = SPP().SPP(epochs[0].date) as PositionResult.Success

    for (epoch in epochs) {

    }

}

/**
 * 构建设计矩阵和观测向量
 * ```
 * | dX   dY   dZ   δt_r  Tz    N1    N2    N3    N4  |
 * |--------------------------------------------------|
 * | a1   b1   c1   1     M1    0     0     0     0   | ← 卫星1伪距
 * | a1   b1   c1   1     M1    λ1    0     0     0   | ← 卫星1载波相位
 * | a2   b2   c2   1     M2    0     0     0     0   | ← 卫星2伪距
 * | a2   b2   c2   1     M2    0     λ2    0     0   | ← 卫星2载波相位
 * | ...                                               |
 * ```
 * @param datas 当前历元下所有可见卫星的观测数据列表
 * @param approxPos 接收机的近似坐标（初始猜测值），格式为 [X0, Y0, Z0]
 * - 含义：接收机的近似坐标（初始猜测值），格式为 [X0, Y0, Z0]。
 * - 单位：米（通常在地心地固坐标系ECEF中）。
 * - 来源：通过单点定位（SPP）粗略估计得到（精度约米级）。在PPP迭代过程中，每次解算后会更新此值。
 * @param clockBias 接收机钟差的初始估计值,单位米
 * @param tropoDelay 对流层天顶方向延迟的初始估计值,单位米
 * @param ambiguities 各卫星载波相位模糊度的初始值,键为卫星PRN，值为模糊度.单位待定
 * @return 设计矩阵H和观测向量L
 */
fun buildDesignMatrixAndObsVector(datas:List<SatelliteData>,
                                  approxPos: DoubleArray,
                                  clockBias: Double,
                                  tropoDelay: Double,
                                  ambiguities: Map<Int, Double>):Pair<Array<DoubleArray>, DoubleArray>
{
    //卫星数
    val numSats = datas.size
    // 3坐标 + 1钟差 + 1对流层 + N个模糊度，这个参数表示设计矩阵的列数
    val numParams = numSats + 5
    // 每颗卫星：伪距 + 载波相位，这个参数表示设计矩阵的行数
    val numObs = 2 * numSats
    // 设计矩阵初始化
    val designMatrix = Array(numObs) { DoubleArray(numParams) }
    //观测向量初始化
    val obsVector = DoubleArray(numObs)

    datas.forEachIndexed { index, satelliteData ->
        val satellitePosition = satelliteData.ephemeris.calculateSatellitePosition(satelliteData.obs.tow.toDouble()-clockBias,satelliteData.obs.IF_combination())
        val rho0 = calculateGeometricRange(approxPos, satellitePosition.toDoubleArray())

        // 几何距离偏导数
        val a = (approxPos[0] - satellitePosition[0]) / rho0  // ∂ρ/∂X
        val b = (approxPos[1] - satellitePosition[1]) / rho0  // ∂ρ/∂Y
        val c = (approxPos[2] - satellitePosition[2]) / rho0  // ∂ρ/∂Z

        // 对流层映射函数（高度角相关）
        val elevation = calculateElevation(approxPos, satellitePosition.toDoubleArray())
        val M = GmfMappingFunction(elevation).first


        val IF_P = satelliteData.obs.IF_combination()
        // 伪距观测行
        designMatrix[2*index][0] = a      // dX
        designMatrix[2*index][1] = b      // dY
        designMatrix[2*index][2] = c      // dZ
        designMatrix[2*index][3] = 1.0    // δt_r
        designMatrix[2*index][4] = M      // Tz
        obsVector[2*index] = IF_P - (rho0 + C * clockBias + tropoDelay * M)


        val carrierPhaseIF = satelliteData.obs.IF_combination2()
        // 载波相位观测行
        designMatrix[2*index+1][0] = a    // dX
        designMatrix[2*index+1][1] = b    // dY
        designMatrix[2*index+1][2] = c    // dZ
        designMatrix[2*index+1][3] = 1.0  // δt_r
        designMatrix[2*index+1][4] = M    // Tz
        designMatrix[2*index+1][5 + index] = satelliteData.obs.wavelengthIF  // N_i
        obsVector[2*index+1] = carrierPhaseIF - (rho0 + C * clockBias + tropoDelay * M - satelliteData.obs.wavelengthIF * ambiguities[satelliteData.obs.prn]!!)
    }

    return Pair(designMatrix, obsVector)
}
/**
 * 计算接收机到卫星的几何距离（欧几里得距离）
 * @param approxPos 接收机近似坐标 [X0, Y0, Z0]（单位：米）
 * @param satPos 卫星坐标 [Xs, Ys, Zs]（单位：米）
 * @return 几何距离 ρ（单位：米）
 * @throws IllegalArgumentException 如果输入坐标维度不为3
 */
fun calculateGeometricRange(approxPos: DoubleArray, satPos: DoubleArray): Double {
    // 检查输入合法性
    require(approxPos.size == 3 && satPos.size == 3) {
        "Input arrays must have exactly 3 elements (X, Y, Z)"
    }

    // 计算坐标差值平方和
    val deltaX = approxPos[0] - satPos[0]
    val deltaY = approxPos[1] - satPos[1]
    val deltaZ = approxPos[2] - satPos[2]
    val sumOfSquares = deltaX.pow(2) + deltaY.pow(2) + deltaZ.pow(2)

    // 返回平方根
    return sqrt(sumOfSquares)
}
/**
 * 计算卫星的仰角
 * @param receiverPos 接收机的ECEF坐标（米）[X, Y, Z]
 * @param satellitePos 卫星的ECEF坐标（米）[X, Y, Z]
 * @return 仰角（度）
 */
fun calculateElevation(
    receiverPos: DoubleArray,  // ECEF [x, y, z]
    satellitePos: DoubleArray  // ECEF [x, y, z]
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
/**
 * 将ECEF（地心地固直角坐标系）坐标转换为大地坐标（纬度、经度、高度）
 *
 * @param x ECEF坐标系下的X坐标（米）
 * @param y ECEF坐标系下的Y坐标（米）
 * @param z ECEF坐标系下的Z坐标（米）
 * @return 大地坐标列表，格式为 [纬度（弧度）, 经度（弧度）, 高度（米）]
 *
 * 算法说明：
 * 1. 经度直接通过反正切计算（atan2(y, x)）
 * 2. 纬度通过迭代法求解（因涉及椭球面非线性方程）
 * 3. 高度基于计算得到的纬度和曲率半径推导
 *
 * 参考：WGS84椭球参数（a=6378137.0, f=1/298.257223563）
 */
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