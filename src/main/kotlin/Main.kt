package org.example

import gnss.GNSSPositioningSystem
import kotlinx.coroutines.*
import org.example.gnss.PositionResult
import kotlin.time.Duration.Companion.seconds


fun main() {
    runBlocking {

        val system = GNSSPositioningSystem()
        val testDuration = 12.5.seconds // 测试持续时间
        // 启动所有协程
        system.startDataCollection()
        val positionFlowJob = launch { system.createPositionFlow() }

        val s = launch {
                system.calculatePosition().collect {
                        result ->
                    when (result) {
                        is PositionResult.Success -> {
                            println("Position: ${result.x}, ${result.y}, ${result.z}")
                        }
                        is PositionResult.Error -> {
                            println(result.message)
                        }
                    }
                }

        }
        println("测试运行中，将持续${testDuration.inWholeSeconds}秒...")


        // 模拟运行一段时间
        // 主线程同时可以做其他事情
        try {
            withTimeout(testDuration) {
                while (true) {
                    delay(500)
                    // 可以在这里打印进度或其他监控信息
//                    println("已收集: ${rawMessages.size}原始消息, ${msm7Messages.size}MSM7消息")
                }
            }
        } catch (e: TimeoutCancellationException) {
            println("${e.message}")
        }

        positionFlowJob.cancel()
        s.cancel()
    }
}