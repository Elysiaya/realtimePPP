package org.example.gnss.ppp

import org.example.gnss.GnssConstants.C
import org.example.gnss.GnssConstants.F1
import org.example.gnss.GnssConstants.F2
import org.example.gnss.PositionResult
import org.example.gnss.SPP
import org.example.gnss.SatelliteData
import org.jetbrains.kotlinx.multik.api.linalg.dot
import org.jetbrains.kotlinx.multik.api.linalg.inv
import org.jetbrains.kotlinx.multik.api.mk
import org.jetbrains.kotlinx.multik.api.ndarray
import org.jetbrains.kotlinx.multik.ndarray.operations.toDoubleArray
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun initvalue(epoch: Epoch):DoubleArray{
    val initialPositionECEF = SPP().SPP(epoch.date) as PositionResult.Success
    var currentPosition = doubleArrayOf(initialPositionECEF.x,initialPositionECEF.y,initialPositionECEF.z)
    //暂时设置为0
    var currentClockBias = initialPositionECEF.clockBias // 接收机钟差,米

    // 3. 初始化PPP参数
    var tropoDelay = 2.3 // 对流层延迟模型
    val ambiguities = mutableMapOf<Int, Double>()  // 模糊度字典（PRN -> 模糊度值）
    epoch.date.forEach {
            s ->
        ambiguities[s.prn] = 0.0
    }
    val ionoDelay = 0.0           // 电离层延迟（可选：双频时消除一阶项）


    val validSats = epoch.date.filter { sat ->
        val satellitePosition = sat.ephemeris.calculateSatellitePosition(sat.obs.tow.toDouble(),sat.obs.pseudoranges[0])
//        println("prn: ${sat.prn} => Elevation${calculateElevation(currentPosition,satellitePosition.toDoubleArray())}")
        calculateElevation(currentPosition,satellitePosition.toDoubleArray()) > 10.0  // 高度角>10度
    }
    val prnList = validSats.map {
        it.prn
    }
    repeat(5){
        val (designMatrix, obsVector) = buildDesignMatrixAndObsVector(
            validSats = validSats,
            approxPos = currentPosition,
            clockBias = currentClockBias,
            tropoDelay = tropoDelay,
            ambiguities = ambiguities
        )
        val x = solveLeastSquares(designMatrix, obsVector)

        //更新参数
        currentPosition = currentPosition.zip(x.take(3)).map {  it.first + it.second }.toDoubleArray()
        currentClockBias = currentClockBias + x[3]
        tropoDelay = tropoDelay+x[4]
        updateAmbiguities(ambiguities, prnList,x.takeLast(prnList.size).toDoubleArray())
    }

    var x = DoubleArray(prnList.size+5)
    x[0] = currentPosition[0]
    x[1] = currentPosition[1]
    x[2] = currentPosition[2]
    x[3] = currentClockBias
    x[4] = tropoDelay

    val ambiguityList = prnList.map { prn -> ambiguities[prn]!! }


    var i = 0
    for (ambiguity in ambiguityList) {
        x[5+i] = ambiguity
        i++
    }

    return x
}

fun updateAmbiguities(
    ambiguities: MutableMap<Int, Double>,
    prnList: List<Int>,          // 当前历元参与解算的卫星PRN列表
    ambiguityDeltas: DoubleArray // 解算得到的模糊度变化量
) {
    prnList.forEachIndexed { index, prn ->
        ambiguities[prn] = ambiguities.getOrDefault(prn, 0.0) + ambiguityDeltas[index]
    }
}


fun solveLeastSquares(designMatrix: Array<DoubleArray>, obsVector: DoubleArray): DoubleArray{

    val A = mk.ndarray(designMatrix)
    val L = mk.ndarray(obsVector)

//    val P = mk.identity<Double>(A.shape[0])
    val  P0 = Array(A.shape[0]){DoubleArray(A.shape[0])}

    for(i in 0 until A.shape[0]){
        if (i%2==0){
            P0[i][i] = 1.0
        }else{
            P0[i][i] = 50.0
        }
    }
    val P = mk.ndarray(P0)


    val Xi = mk.linalg.inv(A.transpose() dot P dot A) dot (A.transpose() dot P dot L)

    return Xi.toDoubleArray()
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
 * | ...                                              |
 * ```
 * @param validSats 当前历元下所有可见卫星的观测数据列表
 * @param approxPos 接收机的近似坐标（初始猜测值），格式为 [X0, Y0, Z0]
 * - 含义：接收机的近似坐标（初始猜测值），格式为 [X0, Y0, Z0]。
 * - 单位：米（通常在地心地固坐标系ECEF中）。
 * - 来源：通过单点定位（SPP）粗略估计得到（精度约米级）。在PPP迭代过程中，每次解算后会更新此值。
 * @param clockBias 接收机钟差的初始估计值,单位米
 * @param tropoDelay 对流层天顶方向延迟的初始估计值,单位米
 * @param ambiguities 各卫星载波相位模糊度的初始值,键为卫星PRN，值为模糊度.单位待定
 * @return 设计矩阵H和观测向量L
 */
fun buildDesignMatrixAndObsVector(validSats:List<SatelliteData>,
                                  approxPos: DoubleArray,
                                  clockBias: Double,//单位米
                                  tropoDelay: Double,
                                  ambiguities: Map<Int, Double>):Pair<Array<DoubleArray>, DoubleArray>
{
    //卫星数
    val numSats = validSats.size
    // 3坐标 + 1钟差 + 1对流层 + N个模糊度，这个参数表示设计矩阵的列数
    val numParams = numSats + 5
    // 每颗卫星：伪距 + 载波相位，这个参数表示设计矩阵的行数
    val numObs = 2 * numSats
    // 设计矩阵初始化
    val designMatrix = Array(numObs) { DoubleArray(numParams) }
    //观测向量初始化
    val obsVector = DoubleArray(numObs)

    validSats.forEachIndexed { index, satelliteData ->
        val satellitePosition = satelliteData.ephemeris.calculateSatellitePosition(satelliteData.obs.tow.toDouble()-clockBias/C,satelliteData.obs.IF_combination())
        val satelliteClockBias = satelliteData.ephemeris.satelliteClockBias
        val rho0 = calculateGeometricRange(approxPos, satellitePosition.toDoubleArray())

        // 几何距离偏导数
        val a = (approxPos[0] - satellitePosition[0]) / rho0  // ∂ρ/∂X
        val b = (approxPos[1] - satellitePosition[1]) / rho0  // ∂ρ/∂Y
        val c = (approxPos[2] - satellitePosition[2]) / rho0  // ∂ρ/∂Z

        // 对流层映射函数（高度角相关）
        val elevation = calculateElevation(approxPos, satellitePosition.toDoubleArray())
        val positionLat = ecefToGeodetic(approxPos[0], approxPos[1], approxPos[2])
        val M = globalMappingFunction(150,positionLat[0], positionLat[1], positionLat[2],elevation).first
//        println("elevation = $elevation M = $M")


        val IF_P = satelliteData.obs.IF_combination()
        // 伪距观测行
        designMatrix[2*index][0] = a      // dX
        designMatrix[2*index][1] = b      // dY
        designMatrix[2*index][2] = c      // dZ
        designMatrix[2*index][3] = 1.0    // δt_r
        designMatrix[2*index][4] = M      // Tz
        obsVector[2*index] = IF_P - (rho0 + clockBias - satelliteClockBias*C + tropoDelay * M)


        val carrierPhaseIF = satelliteData.obs.IF_combination2()
        var wavelengthIF = satelliteData.obs.calculateIfWavelength(F1,F2)
        // 载波相位观测行
        designMatrix[2*index+1][0] = a    // dX
        designMatrix[2*index+1][1] = b    // dY
        designMatrix[2*index+1][2] = c    // dZ
        designMatrix[2*index+1][3] = 1.0  // δt_r
        designMatrix[2*index+1][4] = M    // Tz
        designMatrix[2*index+1][5 + index] = wavelengthIF  // N_i
        obsVector[2*index+1] = carrierPhaseIF - (rho0 + clockBias - satelliteClockBias*C + tropoDelay * M - wavelengthIF * ambiguities[satelliteData.obs.prn]!!)
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
 * @return 仰角（角度）
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