package dev.brella.corsmechanics

import com.arakelian.jq.ImmutableJqLibrary
import com.arakelian.jq.ImmutableJqRequest
import com.arakelian.jq.JqLibrary
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object Jq {
    val library: JqLibrary = ImmutableJqLibrary.of()
    private val filters: MutableMap<String, String> = ConcurrentHashMap()
    private const val idCharacters = "qwertyuiopasdfghjkzxcvbnmQWERTYUIPASDFGHJKLZXCVBNM23456789"
    private val idRandom: Random = Random()

    fun prepareReusable(filter: String): String {
        var id: String

        do {
            id = CharArray(idRandom.nextInt(4) + 4) {
                idCharacters[idRandom.nextInt(idCharacters.length)]
            }.concatToString()
        } while (id in filters)

        filters[id] = filter
        return id
    }

    fun executeReusableRequest(json: String, id: String) =
        ImmutableJqRequest.builder()
            .lib(library)
            .input(json)
            .filter(filters[id] ?: ".")
            .build()
            .execute()

    inline fun executeRequest(json: String, filter: String) =
        ImmutableJqRequest.builder()
            .lib(library)
            .input(json)
            .filter(filter)
            .build()
            .execute()
}