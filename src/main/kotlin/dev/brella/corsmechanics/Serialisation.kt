package dev.brella.corsmechanics

import kotlinx.serialization.json.Json

object Serialisation {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}