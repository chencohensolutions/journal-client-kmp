package com.chencohensolution.journal

import app.cash.sqldelight.driver.native.inMemoryDriver
import com.chencohensolution.journal.db.LibraryDatabase

internal actual fun testLibraryDatabase(): LibraryDatabase = LibraryDatabase(inMemoryDriver(LibraryDatabase.Schema))
