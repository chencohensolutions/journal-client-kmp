package com.chencohensolution.journal.server

/**
 * The names the app gives cameras. The server keeps each file's make and model exactly as its EXIF gives them
 * ("samsung" "SM-S931B"); the phone turns them into names people know ("Samsung Galaxy S25") with these two tables.
 * To name another camera, add its model (as tagged; case doesn't matter, every other character does, so "Galaxy S25"
 * and "Galaxy S25+" stay two cameras) and the whole name to [models]; to spell another maker, add it to [makes].
 */
internal object CameraNames {
    /** Model tag, lowercase, to the camera's whole name. */
    val models: Map<String, String> = listOf(
        // Samsung
        "SM-S931B" to "Samsung Galaxy S25",
        "Galaxy S25" to "Samsung Galaxy S25",
        "SM-S936B" to "Samsung Galaxy S25+",
        "Galaxy S25+" to "Samsung Galaxy S25+",
        "SM-G781B" to "Samsung Galaxy S20 FE 5G",
        "SM-A515F" to "Samsung Galaxy A51",
        "SM-A750F" to "Samsung Galaxy A7 (2018)",
        "SM-G920F" to "Samsung Galaxy S6",
        "GT-I9300" to "Samsung Galaxy S III",
        // Sony
        "H8324" to "Sony Xperia XZ2 Compact",
        "E5823" to "Sony Xperia Z5 Compact",
        "D5503" to "Sony Xperia Z1 Compact",
        // LG
        "LG-E450" to "LG Optimus L5 II",
        // Huawei
        "SLA-L22" to "Huawei P9 lite mini",
    ).associate { (model, name) -> model.lowercase() to name }

    /** Make tag, lowercase, to how the app spells the maker. */
    val makes: Map<String, String> = listOf(
        "samsung" to "Samsung",
        "lge" to "LG",
        "lg electronics" to "LG",
        "nikon corporation" to "Nikon",
        "nikon" to "Nikon",
        "huawei" to "Huawei",
        "sony" to "Sony",
        "canon" to "Canon",
        "apple" to "Apple",
        "htc" to "HTC",
        "google" to "Google",
        "xiaomi" to "Xiaomi",
        "oneplus" to "OnePlus",
        "motorola" to "Motorola",
        "fujifilm" to "Fujifilm",
        "olympus corporation" to "Olympus",
        "olympus imaging corp." to "Olympus",
        "panasonic" to "Panasonic",
    ).toMap()

    /** "samsung" → "Samsung", "NIKON CORPORATION" → "Nikon"; a maker not listed keeps its spelling, with a capital. */
    fun make(make: String): String = makes[make.lowercase()] ?: make.replaceFirstChar { it.uppercaseChar() }
}
