package org.example.gnss.ppp
import org.example.gnss.GnssConstants.C
import org.example.gnss.GnssConstants.F1
import kotlin.collections.toDoubleArray
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.sin

fun main(){
    PPP()
}

fun initializeStateVector(epoch: Epoch, receiverPos: DoubleArray): DoubleArray {
    val numSat = epoch.date.size
    val state = DoubleArray(5 + numSat)

    // 初始位置（可来自伪距单点定位）
    state[0] = receiverPos[0]
    state[1] = receiverPos[1]
    state[2] = receiverPos[2]

    // 初始钟差
    state[3] = 0.0

    // 初始对流层（模型预测或0）
    state[4] = 2.3

    // 初始模糊度
    val lambda = C / F1
    for (i in epoch.date.indices) {
        val obs = epoch.date[i].obs
        val eph = epoch.date[i].ephemeris
        val satPos = eph.calculateSatellitePosition(obs.tow.toDouble(),obs.pseudoranges[0])
        val rho = sqrt(((receiverPos[0]-satPos[0])*(receiverPos[0]-satPos[0])
                + (receiverPos[1]-satPos[1])*(receiverPos[1]-satPos[1])
                + (receiverPos[2]-satPos[2])*(receiverPos[2]-satPos[2])))
        val satClockBiasMeters = eph.satelliteClockBias * C
        state[5 + i] = (obs.phaseranges[0] - rho - state[3] + satClockBiasMeters) / lambda
    }

    return state
}
/**
 * PPP定位核心代码
 *
 */
fun PPP() {

    val X = -0.405205293920631E+07
    val Y = 0.421283594860667E+07
    val Z = -.254510429759719E+07
    val epochs = getdata()
    val numsat =  epochs[0].date.size
    val receiverPosition = doubleArrayOf(X,Y,Z)
    val x = initializeStateVector(epochs[0],receiverPosition)
    // 打印状态向量（分项标注）
    println("=== State Vector ===")
    println("Receiver Position (ECEF):")
    println("  X: ${x[0]} m")
    println("  Y: ${x[1]} m")
    println("  Z: ${x[2]} m")
    println("Receiver Clock Bias: ${x[3]} m (≈${x[3] / C * 1e9} ns)")
    println("Tropospheric Delay: ${x[4]} m")

    println("Carrier Phase Ambiguities (L1):")
    for (i in 5 until x.size) {
        val satPrn = epochs[0].date[i-5].obs.prn  // 假设观测数据包含PRN号
        println("  Sat $satPrn (N${i-4}): ${x[i]} m (${x[i] / (C/F1)} cycles)")
    }

// 打印原始数组（备用）
    println("\nRaw State Vector Array:")
    println(x.contentToString())
    val s = x.size

    val initialCovariance = Array(s) { i ->
        DoubleArray(s).apply {
            this[i] = when {
                i < 3 -> 100.0 * 100.0    // 位置方差 (100 m)^2
                i == 3 -> 50 * 50.0        // 钟差方差
                i == 4 -> 3.0 * 3.0        // 对流层方差
                else -> 50 * 50.0          // 模糊度方差
            }
        }
    }
    val processNoise = Array(s) { i ->
        DoubleArray(s).apply {
            this[i] = when {
                i < 3 -> 1e-6    // 位置过程噪声
                i == 3 -> 0.1     // 钟差过程噪声
                i == 4 -> 1e-4    // 对流层过程噪声
                else -> 0.0       // 模糊度不变
            }
        }
    }
    val measurementNoise = Array(numsat*2) { i ->
        DoubleArray(numsat*2).apply {
            this[i] = if (i % 2 == 0) 1.0 else 0.001  // 伪距噪声 1.0，载波相位噪声 0.01
        }
    }
    val stateTransition = Array(s) { i ->
        DoubleArray(s).apply {
            this[i] = 1.0  // 单位矩阵
        }
    }

    val kalmanFilter = KalmanFilter(
        initialEstimate = x,
        initialCovariance =initialCovariance,
        processNoise = processNoise,
        measurementNoise = measurementNoise,
        stateTransition =  stateTransition,
    )

    var r = receiverPosition


    for (epoch in epochs.take(20)) {

        val validSats = epoch.date.filter { sat ->
            val satellitePosition = sat.ephemeris.calculateSatellitePosition(sat.obs.tow.toDouble(),sat.obs.pseudoranges[0])
            calculateElevation(receiverPosition,satellitePosition.toDoubleArray()) > 10.0  // 高度角>10度
        }

        kalmanFilter.predict()
        val measurement = DoubleArray(validSats.size*2) { 0.0 }
        for (i in validSats.indices) {
            // 使用单频观测
            measurement[i*2] = validSats[i].obs.pseudoranges[0]
            measurement[i*2+1] = validSats[i].obs.phaseranges[0]
        }
        kalmanFilter.update(measurement = measurement,epoch = Epoch(date = validSats), receiverPosition = r)
        r = kalmanFilter.getCurrentState().toDoubleVector().take(3).toDoubleArray()
        println("CurrentState: ${kalmanFilter.getCurrentState().toDoubleVector().contentToString()}")
        println("=====================================================")
    }
}