package com.eter.mitienda

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform