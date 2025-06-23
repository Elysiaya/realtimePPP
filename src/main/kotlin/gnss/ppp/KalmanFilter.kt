package org.example.gnss.ppp

import org.example.gnss.GnssConstants.C
import org.example.gnss.GnssConstants.F1
import org.example.gnss.GnssConstants.F2
import org.jetbrains.kotlinx.multik.ndarray.data.DataType
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.inverse.InvertMatrix
import kotlin.collections.forEachIndexed
import kotlin.collections.toDoubleArray
import kotlin.math.sqrt

/**
 * 多维卡尔曼滤波器
 * @param initialEstimate 初始状态估计向量 X
 * @param initialCovariance 初始协方差矩阵 P
 * @param processNoise 过程噪声矩阵 Q
 * @param measurementNoise 测量噪声矩阵 R
 * @param stateTransition 状态转移矩阵F
 * @param observationMatrix 观测矩阵H
 */
class KalmanFilter(
    initialEstimate: DoubleArray,
    initialCovariance: Array<DoubleArray>,
    private val processNoise: Array<DoubleArray>,
    private val measurementNoise: Array<DoubleArray>,
    private val stateTransition: Array<DoubleArray>,
) {
    // 当前状态估计 (x̂)
    private var stateEstimate: INDArray = Nd4j.create(initialEstimate)

    // 当前协方差矩阵 (P)
    private var estimateCovariance: INDArray = Nd4j.create(initialCovariance)

    // 预创建的矩阵对象（避免重复创建）
    private val F: INDArray = Nd4j.create(stateTransition)
    private val Q: INDArray = Nd4j.create(processNoise)
    private val R: INDArray = Nd4j.create(measurementNoise)

    // 临时矩阵（用于计算）
    private val tempMatrix: INDArray
    private val identityMatrix: INDArray

    init {
        val n = stateEstimate.length()
        tempMatrix = Nd4j.create(n, n)
        identityMatrix = Nd4j.eye(n)
    }

    /**
     * 预测步骤
     */
    fun predict(): INDArray {
        // 状态预测: x̂ₖ⁻ = F x̂ₖ₋₁
        stateEstimate = F.mmul(stateEstimate)

        // 协方差预测: Pₖ⁻ = F Pₖ₋₁ Fᵀ + Q
        estimateCovariance = F.mmul(estimateCovariance).mmul(F.transpose()).add(Q)

        return stateEstimate.dup()
    }

    /**
     * 更新步骤
     * @param measurement 测量值向量
     */
    fun update(measurement: DoubleArray,epoch: Epoch,receiverPosition: DoubleArray): INDArray {

        val measurementVec = Nd4j.create(measurement)

        val H = computeJacobian(epoch, stateEstimate)

        // 计算残差: y = z - H x̂⁻
        val predictedMeas = computePredictedMeasurements(epoch, stateEstimate)
        val measurementResidual = measurementVec.sub(predictedMeas)
        println("measurementResidual: $measurementResidual")

        // 计算残差协方差: S = H P⁻ Hᵀ + R
        val residualCovariance = H.mmul(estimateCovariance)
            .mmul(H.transpose())
            .add(R)

        // 计算卡尔曼增益: K = P⁻ Hᵀ S⁻¹
        val kalmanGain = estimateCovariance.mmul(H.transpose())
            .mmul(InvertMatrix.invert(residualCovariance, false))

        // 更新状态估计: x̂ = x̂⁻ + K y
        stateEstimate = stateEstimate.add(kalmanGain.mmul(measurementResidual))

        // 更新协方差估计: P = (I - K H) P⁻
        tempMatrix.assign(identityMatrix).subi(kalmanGain.mmul(H))
        estimateCovariance = tempMatrix.castTo(org.nd4j.linalg.api.buffer.DataType.DOUBLE).mmul(estimateCovariance)

        return stateEstimate.dup()
    }
    fun computePredictedMeasurements(
        epoch: Epoch,
        stateEstimate: INDArray
    ): INDArray {
        val numSats = epoch.date.size
        val predicted = DoubleArray(numSats * 2) // 伪距 + 相位测量

        val rxX = stateEstimate.getDouble(0)
        val rxY = stateEstimate.getDouble(1)
        val rxZ = stateEstimate.getDouble(2)
        val rxClock = stateEstimate.getDouble(3)    // 单位 m
        val tropo = stateEstimate.getDouble(4)      // 对流层延迟

        for (i in epoch.date.indices) {
            val satData = epoch.date[i]
            val satPos = satData.ephemeris.calculateSatellitePosition(
                satData.obs.tow.toDouble(),
                satData.obs.pseudoranges[0]
            )

            // 几何距离
            val dx = rxX - satPos[0]
            val dy = rxY - satPos[1]
            val dz = rxZ - satPos[2]
            val rho = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)

            // 卫星钟差修正
            val satClock = satData.ephemeris.satelliteClockBias * C

            // 整周模糊度对应的项
            val N = stateEstimate.getDouble(5 + i) // 第 i 颗卫星对应 N
            val lambda = C / F1

            // 模型预测伪距（单位 m）
            val predictedPseudorange = rho + rxClock - satClock + tropo

            // 模型预测相位观测 = 几何距离 + 时钟 - 整周模糊度λN + 对流层
            val predictedPhase = rho + rxClock - satClock + tropo + N * lambda

            predicted[i * 2] = predictedPseudorange
            predicted[i * 2 + 1] = predictedPhase
        }

        return Nd4j.create(predicted)
    }
    // 雅可比计算
    fun computeJacobian(
        epoch: Epoch,
        stateEstimate: INDArray
    ): INDArray {
        val numSats = epoch.date.size
        val stateSize = stateEstimate.length()
        val H = Nd4j.zeros(
            org.nd4j.linalg.api.buffer.DataType.DOUBLE,  // 指定为双精度
            (numSats * 2).toLong(),
            stateSize
        )

        val rxX = stateEstimate.getDouble(0)
        val rxY = stateEstimate.getDouble(1)
        val rxZ = stateEstimate.getDouble(2)

        val receiverPosition = doubleArrayOf(rxX, rxY, rxZ)

        for (i in epoch.date.indices) {
            val satData = epoch.date[i]
            val satPos = satData.ephemeris.calculateSatellitePosition(
                satData.obs.tow.toDouble(),
                satData.obs.pseudoranges[0]
            )
            val dx = rxX - satPos[0]
            val dy = rxY - satPos[1]
            val dz = rxZ - satPos[2]
            val rho = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)

            // 对流层映射函数（高度角相关）
            val elevation = calculateElevation(receiverPosition, satPos.toDoubleArray())
            val positionLat = ecefToGeodetic(receiverPosition[0], receiverPosition[1], receiverPosition[2])
            val M = globalMappingFunction(150,positionLat[0], positionLat[1], positionLat[2],elevation).first

            val i1 = (i*2).toLong()
            // 对伪距测量，雅可比
            H.putScalar(i1, 0, dx / rho)  // dρ/dx
            H.putScalar(i1, 1, dy / rho)
            H.putScalar(i1, 2, dz / rho)
            H.putScalar(i1, 3, 1.0)       // dρ/dClock
            H.putScalar(i1, 4, 1.0)       // dρ/dTropo
            // 整周模糊度对伪距影响为 0

            val i2 = (i*2+1).toLong()
            // 对相位测量，雅可比与伪距相同 + 模糊度部分
            H.putScalar(i2, 0, dx / rho)
            H.putScalar(i2, 1, dy / rho)
            H.putScalar(i2, 2, dz / rho)
            H.putScalar(i2, 3, 1.0)
            H.putScalar(i2, 4, 1.0)
            val lambda = C / F1
            H.putScalar(i2, (5 + i).toLong(), lambda) // 对应 N
        }

        return H
    }

    /**
     * 计算观测矩阵
     * @param epoch 该批次的观测信息
     * @return H观测矩阵
     */
    fun computeObservationMatrix(epoch: Epoch,receiverPosition: DoubleArray): Array<DoubleArray> {
        val numSatellites = epoch.date.size
        val stateSize = 5+numSatellites
        val hMatrix = Array(numSatellites * 2) { DoubleArray(stateSize) }

        for (i in epoch.date.indices) {
            val obs = epoch.date[i].obs
            val eph = epoch.date[i].ephemeris
            val satellitePosition =  eph.calculateSatellitePosition(obs.tow.toDouble(),obs.pseudoranges[0]).toDoubleArray()

            // 计算几何导数（符号修正）
            val dx = satellitePosition[0] - receiverPosition[0]
            val dy = satellitePosition[1] - receiverPosition[1]
            val dz = satellitePosition[2] - receiverPosition[2]

            val range = sqrt(dx * dx + dy * dy + dz * dz)

            // 对流层映射函数（高度角相关）
            val elevation = calculateElevation(receiverPosition, satellitePosition)
            val positionLat = ecefToGeodetic(receiverPosition[0], receiverPosition[1], receiverPosition[2])
            val M = globalMappingFunction(150,positionLat[0], positionLat[1], positionLat[2],elevation).first

            // 伪距观测行
            hMatrix[i*2][0] = dx / range  // dρ/dX
            hMatrix[i*2][1] = dy / range  // dρ/dY
            hMatrix[i*2][2] = dz / range  // dρ/dZ
            hMatrix[i*2][3] = 1.0         // dρ/d(钟差)
            hMatrix[i*2][4] = M         // dρ/d(对流层)


            // 载波相位观测行
            hMatrix[i*2+1][0] = dx / range  // dΦ/dX
            hMatrix[i*2+1][1] = dy / range  // dΦ/dY
            hMatrix[i*2+1][2] = dz / range  // dΦ/dZ
            hMatrix[i*2+1][3] = 1.0         // dΦ/d(钟差)
            hMatrix[i*2+1][4] = M         // dΦ/d(对流层)
            hMatrix[i*2+1][5 + i] = -C/F1     // dΦ/d(模糊度i)
        }
        return hMatrix
    }

    // 获取当前状态估计
    fun getCurrentState(): INDArray = stateEstimate.dup()

    // 获取当前协方差矩阵
    fun getCurrentCovariance(): INDArray = estimateCovariance.dup()
}
