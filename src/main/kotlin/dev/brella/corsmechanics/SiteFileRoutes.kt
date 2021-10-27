package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.Caffeine
import dev.brella.kornea.blaseball.base.common.beans.Colour
import dev.brella.kornea.blaseball.base.common.beans.ColourAsHexSerialiser
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.future.await
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

class SiteFileRoutes(val application: Application, val httpClient: HttpClient) {

    @Serializable
    data class GitCommit(
        val sha: String,
        val node_id: String,
        val commit: GitCommitDetails,
        val url: String,
        val html_url: String,
        val comments_url: String,
        val author: GitAuthor?,
        val committer: GitAuthor?,
        val parents: List<GitCommitParent>
    )

    @Serializable
    data class GitCommitDetails(
        val author: GitAuthor?,
        val committer: GitAuthor?,
        val message: String,
        val tree: GitCommitTree,
        val url: String,
        val comment_count: Int
    )

    @Serializable
    data class GitAuthor(
        val name: String?,
        val email: String?,
        val date: Instant
    )

    @Serializable
    data class GitCommitTree(
        val sha: String,
        val url: String
    )

    @Serializable
    data class GitCommitParent(
        val sha: String,
        val url: String,
        val html_url: String
    )

    @Serializable
    data class BlaseballAttribute(
        val id: String,
        val color: @Serializable(ColourAsHexSerialiser::class) Colour,
        val textColor: @Serializable(ColourAsHexSerialiser::class) Colour,
        val background: @Serializable(ColourAsHexSerialiser::class) Colour,
        val title: String,
        val description: String,
        val descriptions: Map<String, String> = emptyMap()
    )

    suspend inline fun HttpClient.getCommitsFor(owner: String, repo: String, sha: String? = null, path: String? = null, author: String? = null, since: Instant? = null, until: Instant? = null, perPage: Int? = null, page: Int? = null) =
        get<List<GitCommit>>("https://api.github.com/repos/$owner/$repo/commits") {
            sha?.let { parameter("sha", it) }
            path?.let { parameter("path", it) }
            author?.let { parameter("author", it) }
            since?.let { parameter("since", it) }
            until?.let { parameter("until", it) }
            perPage?.let { parameter("per_page", it.coerceIn(1, 100)) }
            page?.let { parameter("page", it) }
        }

    suspend inline fun <reified T> HttpClient.getJsonFromRaw(url: String, builder: HttpRequestBuilder.() -> Unit = {}): T? =
        feature(JsonFeature)?.serializer?.read(typeInfo<T>(), get(url, builder)) as? T

    val LATEST_COMMIT_FOR_FILE = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .buildCoroutines<Pair<String?, String>, GitCommit> { (time, path) ->
            val time = time?.takeUnless { it == "NOW" }?.let(Instant.Companion::parse) ?: Clock.System.now()

            httpClient.getCommitsFor("xSke", "blaseball-site-files", path = path, until = time, perPage = 1)
                .first()
        }

    val ATTRIBUTES_BY_TIME = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<String, Map<String, BlaseballAttribute>> { time ->
            val latestCommit = LATEST_COMMIT_FOR_FILE[time to "data/attributes.json"].await()

            return@buildCoroutines httpClient.getJsonFromRaw<List<BlaseballAttribute>>("https://raw.githubusercontent.com/xSke/blaseball-site-files/${latestCommit.sha}/data/attributes.json")!!
                .associateBy(BlaseballAttribute::id)
        }

    val WEATHER_BY_TIME = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<String, Map<String, BlaseballAttribute>> { time ->
            val latestCommit = LATEST_COMMIT_FOR_FILE[time to "data/attributes.json"].await()

            return@buildCoroutines httpClient.getJsonFromRaw<List<BlaseballAttribute>>("https://raw.githubusercontent.com/xSke/blaseball-site-files/${latestCommit.sha}/data/attributes.json")!!
                .associateBy(BlaseballAttribute::id)
        }

    val FROM_SITE_BY_TIME = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .buildCoroutines<Pair<String?, String>, ProxiedResponse> { pair ->
            val latestCommit = LATEST_COMMIT_FOR_FILE[pair].await()

            return@buildCoroutines httpClient.get<HttpResponse>("https://raw.githubusercontent.com/xSke/blaseball-site-files/${latestCommit.sha}/${pair.second}")
                .proxy()
        }

    val CONTENT_TYPES = mapOf(
        "2.js" to "application/javascript",
        "main.js" to "application/javascript",
        "index.html" to "text/html",
        "main.css" to "text/css",

        "data/attributes.json" to "application/json",
        "data/glossary.json" to "application/json",
        "data/glossary.md" to "text/markdown",
        "data/items.json" to "application/json",
        "data/library.json" to "application/json",
        "data/thebook.md" to "text/markdown",
        "data/weather.json" to "application/json"
    )

    init {
        with(application) {
            routing {
                get("/from_site/by_time/{path...}") {
                    val path = call.parameters.getAll("path")?.joinToString("/") ?: return@get
                    val contentType = CONTENT_TYPES[path]
                    if (contentType != null) {
                        call.respondProxied(FROM_SITE_BY_TIME[call.request.queryParameters["time"] to path].await(), headersOf(HttpHeaders.ContentType, contentType))
                    } else {
                        call.respondProxied(FROM_SITE_BY_TIME[call.request.queryParameters["time"] to path].await())
                    }
                }
                get("/attributes/by_time") {
                    call.respond(ATTRIBUTES_BY_TIME[call.request.queryParameters["time"] ?: "NOW"].await())
                }
                get("/attributes/by_time/{id}") {
                    val id = call.parameters["id"] ?: return@get
                    val attributes = ATTRIBUTES_BY_TIME[call.request.queryParameters["time"] ?: "NOW"].await()

                    call.respond(attributes[id] ?: return@get)
                }
            }
        }
    }
}