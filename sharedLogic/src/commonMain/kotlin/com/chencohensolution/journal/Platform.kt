package com.chencohensolution.journal

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform