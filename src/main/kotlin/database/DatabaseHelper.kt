package org.example.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

fun initDatabase() {
    // 连接 SQLite 数据库
    Database.connect(
        "jdbc:sqlite:gnss_data.db",
        driver = "org.sqlite.JDBC"
    )

    // 创建表
    transaction {
        SchemaUtils.create(
            SatelliteEphemeris,
            SignalObservation,
        )
    }
}

fun main() {
    initDatabase()
}
