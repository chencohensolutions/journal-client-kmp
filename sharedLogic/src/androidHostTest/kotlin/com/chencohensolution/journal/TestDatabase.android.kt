package com.chencohensolution.journal

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.chencohensolution.journal.db.LibraryDatabase
import java.util.Properties

internal actual fun testLibraryDatabase(): LibraryDatabase =
    LibraryDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), LibraryDatabase.Schema))
