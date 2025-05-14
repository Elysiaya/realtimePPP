import gnss.SatelliteData
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

import org.jetbrains.kotlinx.multik.api.*
import org.jetbrains.kotlinx.multik.api.linalg.*
import org.jetbrains.kotlinx.multik.ndarray.data.*
import org.jetbrains.kotlinx.multik.ndarray.operations.toList
import kotlin.test.assertEquals

class GNSSPositioningTest {
    val Radv = 7.2921150e-5  // 地球自转角速度（rad/s）
    val GM = 3.986005e14  // 地球引力常数GM（m^3/s^2）
    val C = 2.99792458e8  // 真空中的光速（m/s）

    @Test
    fun satelliteDataTest()
    {
        val gnssdata: List<SatelliteData> = Json.decodeFromString(File("debug_snapshot5.json").readText())

        val X0 = mutableListOf(1.0,1.0,1.0,0.0)

        for(i in 0..20){

            val r = computer(gnssdata,X0.toTypedArray(),0,0.0)

            val A = mk.ndarray(r.first.toTypedArray())
            val L = mk.ndarray(r.second.toDoubleArray())

            val P = mk.identity<Double>(A.shape[0])

            val Xi = mk.linalg.inv(A.transpose() dot P dot A) dot (A.transpose() dot P dot L)


            if (Xi[0] > 1e-6 || Xi[1] > 1e-6 || Xi[2] > 1e-6) {
                for(i in X0.indices){
                    X0[i] = X0[i] + Xi[i]
                }
            }
            else{
                println("在高度角范围内的卫星有${A.shape[0]}颗")
                println("接收机坐标为:${X0.take(3)}")
                println("接收机钟差为:${X0[3] / C}")

                val X = -4052053.9123
                val Y = 4212836.8035
                val Z = -2545104.6562

                println("DelateX = ${X0[0]-X}")
                println("DelateY = ${X0[1]-Y}")
                println("DelateZ = ${X0[2]-Z}")
                break
            }
        }


    }
    @Test
    fun satellitePositionTest(){
        val gnssdata: List<SatelliteData> = Json.decodeFromString(File("debug_snapshot.json").readText())
        for (data in gnssdata){
            data.ephemeris.idot = data.ephemeris.idot * Math.PI
        }
        val p = gnssdata.first().ephemeris.calculateSatellitePosition(561600.0-100)
        println(p)
    }
    fun computer(gnssdata:List<SatelliteData>,
                 X0:Array<Double>,
                 iter:Int,
                 cutoff_angle:Double):Pair<MutableList<DoubleArray>, MutableList<Double>> {

        val A = mutableListOf<DoubleArray>()
        val L = mutableListOf<Double>()

        for (data in gnssdata) {

            if (data.obs.CNRs.first()<36) continue
            val observations_time = data.obs.tow


            //设置初始的信号传播时间
            var t0si = data.obs.pseudorange.first()/C

            repeat(3) {
                //计算信号发射时间
                val transmit_time = observations_time - t0si
                // 2. 计算卫星钟差（基于发射时间）
                val dts = data.ephemeris.calculateClockOffset(transmit_time)
                // 3. 更新传播时间（加入钟差补偿）
                t0si = data.obs.pseudorange.first() / C - (X0[3] / C) + dts
            }

            var Tsi = 0.0
            var satellite_position: List<Double>
            var Rsr = 0.0
            while (true) {
                Tsi = observations_time - t0si
                satellite_position = data.ephemeris.calculateSatellitePosition(Tsi)
                //进行地球自转改正
                val a = Radv * t0si

                val rotationMatrix = mk.ndarray(arrayOf(
                    doubleArrayOf(cos(a), -sin(a), 0.0),
                    doubleArrayOf(sin(a), cos(a), 0.0),
                    doubleArrayOf(0.0, 0.0, 1.0)
                ))
                // 创建卫星位置向量（3x1矩阵）
                val satPosVector = mk.ndarray(satellite_position).reshape(3, 1)

                satellite_position = (rotationMatrix dot satPosVector).reshape(3).toList()

                fun Double.square() = this * this
//                计算卫星和测站的几何距离
                Rsr = sqrt((satellite_position[0] - X0[0]).square() + (satellite_position[1] - X0[1]).square() + (satellite_position[2] - X0[2]).square())
                val t1si = Rsr / C
                if (abs(t1si - t0si) < 10e-7) {
                    break
                } else {
                    t0si = t1si
                }}

                val b0si = (X0[0] - satellite_position[0]) / Rsr
                val b1si = (X0[1] - satellite_position[1]) / Rsr
                val b2si = (X0[2] - satellite_position[2]) / Rsr
                val b3si = 1.0

                val diono = 0  // 电离层延迟改正量，采用无电离层伪距观测组合值时此项为0
                val D_RTCM = 0  // 对伪距的差分改证
                val D_troposphere = 0//对流层
                val dts = data.ephemeris.calculateClockOffset(Tsi)
                val dtprel = 0//相对论效应改正

                val l = data.obs.pseudorange.first() - Rsr + C * (dts + dtprel) - D_troposphere - diono + D_RTCM

                A.add(doubleArrayOf(b0si, b1si, b2si, b3si))
                L.add(l)
            }
        return Pair(A, L)
        }

    fun multiplyMatrices(matrix1: Array<DoubleArray>, matrix2: Array<DoubleArray>): Array<Array<Double>> {
        val row1 = matrix1.size
        val col1 = matrix1[0].size
        val col2 = matrix2[0].size
        val product = Array(row1) { Array(col2) { 0.0 } }

        for (i in 0 until row1) {
            for (j in 0 until col2) {
                for (k in 0 until col1) {
                    product[i][j] += matrix1[i][k] * matrix2[k][j]
                }
            }
        }
        return product
    }

    @Test
    fun matrixTest1(){
        val A = mk.ndarray(mk[mk[1,2],mk[3,4]])
        val B = mk.ndarray(mk[mk[0,0],mk[1,1]])

        val actualC = A dot B // 矩阵乘法
        val expectedC = mk.ndarray(mk[mk[2, 2], mk[4, 4]])

        // 验证结果是否相等
        assertEquals(expectedC, actualC)
    }

    @Test
    fun matrixTest2(){
        val A = mk.ndarray(mk[mk[1,2],mk[3,4]])
        val B = mk.ndarray(mk[1,1])

        val actualC = A dot B // 矩阵乘法
        val expectedC = mk.ndarray(mk[3,7])

        println(actualC)


        // 验证结果是否相等
        assertEquals(expectedC, actualC)
    }
    @Test
    fun matrixTest3(){
        val rotationMatrix = mk.ndarray(arrayOf(
            doubleArrayOf(1.0,2.0, 0.0),
            doubleArrayOf(3.0,4.0,0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        ))

        println(rotationMatrix)
    }

}