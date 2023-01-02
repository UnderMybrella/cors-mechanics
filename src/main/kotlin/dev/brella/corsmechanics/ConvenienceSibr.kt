package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.Caffeine
import dev.brella.kornea.blaseball.base.common.*
import dev.brella.kornea.blaseball.base.common.beans.BlaseballStandings
import dev.brella.kornea.blaseball.base.common.beans.BlaseballTiebreaker
import dev.brella.kornea.blaseball.base.common.beans.Colour
import dev.brella.kornea.blaseball.base.common.beans.ColourAsHexSerialiser
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.datetime.Instant
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit
import kotlinx.datetime.Clock as ClockKt
import kotlinx.serialization.Serializable as Serializable
import java.time.Clock as ClockJt

@Serializable
data class ConvenienceLeague(
    val id: LeagueID,
    val subleagues: List<ConvenienceSubLeague>,
    val name: String,
    val tiebreakers: List<BlaseballTiebreaker>,
    val tiebreakersID: TiebreakerID,
)

@Serializable
data class ConvenienceSubLeague(
    val id: SubleagueID,
    val divisions: List<ConvenienceDivision>,
    val name: String,
)

@Serializable
data class ConvenienceDivision(
    val id: DivisionID,
    val teams: List<ChroniclerBlaseballTeam>,
    val name: String,
)

@Serializable
data class ChroniclerBlaseballTeam(
    val id: TeamID,
    val lineup: List<PlayerID>,
    val rotation: List<PlayerID>,
    val bullpen: List<PlayerID>,
    val bench: List<PlayerID>,
    val fullName: String,
    val location: String,
    val mainColor: @Serializable(ColourAsHexSerialiser::class) Colour,
    val nickname: String,
    val secondaryColor: @Serializable(ColourAsHexSerialiser::class) Colour,
    val shorthand: String,
    val emoji: String,
    val slogan: String,
    val shameRuns: Double,
    val totalShames: Int,
    val totalShamings: Int,
    val seasonShames: Int,
    val seasonShamings: Int,
    val championships: Int,
    val rotationSlot: Int? = null,
    val weekAttr: List<ModificationID>,
    val gameAttr: List<ModificationID>,
    val seasAttr: List<ModificationID>,
    val permAttr: List<ModificationID>,
    val teamSpirit: Double? = null,
    val card: Int? = null,
    /*   */
    val tournamentWins: Int? = null,
    val stadium: StadiumID? = null,
    val imPosition: Double? = null,
    val eDensity: Double? = null,
    val eVelocity: Double? = null,
    val state: JsonElement? = null,
    val evolution: Double? = null,
    val winStreak: Double? = null,
    val level: Int? = null,
)

@Serializable
data class ChroniclerV2Data<T>(val nextPage: String? = null, val items: List<ChroniclerV2Item<T>> = emptyList())

@Serializable
data class ChroniclerV2Item<T>(
    val entityId: String,
    val hash: String,
    val validFrom: Instant,
    val validTo: Instant?,
    val data: T,
)

@Serializable
data class ConvenienceGameStatsheet(
    val id: GameStatsheetID,
    val homeTeamRunsByInning: List<Double>,
    val awayTeamRunsByInning: List<Double>,
    val awayTeamTotalBatters: Int,
    val homeTeamTotalBatters: Int,
    val awayTeamStats: ConvenienceTeamStatsheet?,
    val homeTeamStats: ConvenienceTeamStatsheet?,
    val awayTeamStatsheetID: TeamStatsheetID,
    val homeTeamStatsheetID: TeamStatsheetID,
)

@Serializable
data class ConvenienceTeamStatsheet(
    val id: TeamStatsheetID,
    val playerStats: List<ConveniencePlayerStatsheet>,
    val playerStatIDs: List<PlayerStatsheetID>,
    val gamesPlayed: Int,
    val wins: Int,
    val losses: Int,
    val name: String,
    val teamId: TeamID,
)

@Serializable
data class ConveniencePlayerStatsheet(
    val id: PlayerStatsheetID,
    val playerId: PlayerID?,
    val teamId: TeamID?,
    val team: String,
    val name: String,
    val atBats: Int,
    val caughtStealing: Int,
    val doubles: Int,
    val earnedRuns: Double,
    val groundIntoDp: Int,
    val hits: Int,
    val hitsAllowed: Int,
    val homeRuns: Int,
    val losses: Int,
    val outsRecorded: Int,
    val rbis: Int,
    val runs: Int,
    val stolenBases: Int,
    val strikeouts: Int,
    val struckouts: Int,
    val triples: Int,
    val walks: Int,
    val walksIssued: Int,
    val wins: Int,
    val hitByPitch: Int? = null,
    val hitBatters: Int? = null,
    val quadruples: Int? = null,
    val pitchesThrown: Int? = null,
)

@Serializable
data class ConvenienceSeasonStatsheet(
    val id: SeasonStatsheetID,
    val teamStats: List<ConvenienceTeamStatsheet> = emptyList(),
    val teamStatIDs: List<TeamStatsheetID>,
)

@Serializable
data class BlaseballGameStatsheet(
    val id: GameStatsheetID,
    val homeTeamRunsByInning: List<Double>,
    val awayTeamRunsByInning: List<Double>,
    val awayTeamTotalBatters: Int,
    val homeTeamTotalBatters: Int,
    val awayTeamStats: TeamStatsheetID,
    val homeTeamStats: TeamStatsheetID,
)

@Serializable
data class BlaseballSeasonData(
    val id: SeasonID? = null,
    val league: LeagueID,
    val rules: RulesID,
    val schedule: ScheduleID,
    val seasonNumber: Int,
    val standings: StandingsID,
    val stats: SeasonStatsheetID,
    val terminology: TerminologyID,
)

@Serializable
data class ConvenienceSeasonData(
    val id: SeasonID? = null,
    val league: ConvenienceLeague?,
    val leagueID: LeagueID,
    val rules: RulesID,
    val schedule: ScheduleID,
    val seasonNumber: Int,
    val standings: BlaseballStandings?,
    val standingsID: StandingsID,
    val stats: ConvenienceSeasonStatsheet?,
    val statsID: SeasonStatsheetID,
    val terminology: TerminologyID,
)

@Serializable
data class BlaseballPlayerStatsheet(
    val id: PlayerStatsheetID,
    val playerId: PlayerID?,
    val teamId: TeamID?,
    val team: String,
    val name: String,
    val atBats: Int,
    val caughtStealing: Int,
    val doubles: Int,
    val earnedRuns: Double,
    val groundIntoDp: Int,
    val hits: Int,
    val hitsAllowed: Int,
    val homeRuns: Int,
    val losses: Int,
    val outsRecorded: Int,
    val rbis: Int,
    val runs: Double,
    val stolenBases: Int,
    val strikeouts: Int,
    val struckouts: Int,
    val triples: Int,
    val walks: Int,
    val walksIssued: Int,
    val wins: Int,
    val hitByPitch: Int? = null,
    val hitBatters: Int? = null,
    val quadruples: Int? = null,
    val pitchesThrown: Int? = null,
)

suspend inline fun <reified T> HttpClient.chroniclerVersionMostRecent(
    chroniclerHost: String,
    type: String,
    builder: HttpRequestBuilder.() -> Unit = {},
): T =
    get("$chroniclerHost/v2/entities") {
        parameter("type", type)

        builder()
    }.body<ChroniclerV2Data<T>>().items.first().data

suspend inline fun <reified T> HttpClient.chroniclerVersionList(
    chroniclerHost: String,
    type: String,
    builder: HttpRequestBuilder.() -> Unit = {},
): List<T> =
    get("$chroniclerHost/v2/entities") {
        parameter("type", type)

        builder()
    }.body<ChroniclerV2Data<T>>().items.map(ChroniclerV2Item<T>::data)

suspend inline fun <reified T> HttpClient.chroniclerVersionListItems(
    chroniclerHost: String,
    type: String,
    builder: HttpRequestBuilder.() -> Unit = {},
): List<ChroniclerV2Item<T>> =
    get("$chroniclerHost/v2/entities") {
        parameter("type", type)

        builder()
    }.body<ChroniclerV2Data<T>>().items

suspend inline fun <reified T> HttpClient.chroniclerEntityList(
    chroniclerHost: String,
    type: String,
    at: String,
    builder: HttpRequestBuilder.() -> Unit = {},
): List<T> =
    get("$chroniclerHost/v2/entities") {
        parameter("type", type)
        parameter("at", at)

        builder()
    }.body<ChroniclerV2Data<T>>().items.map(ChroniclerV2Item<T>::data)

suspend inline fun <reified T> HttpClient.chroniclerEntityMostRecent(
    chroniclerHost: String,
    type: String,
    at: String,
    builder: HttpRequestBuilder.() -> Unit = {},
): T =
    get("$chroniclerHost/v2/entities") {
        parameter("type", type)
        parameter("at", at)

        builder()
    }.body<ChroniclerV2Data<T>>().items.maxByOrNull(ChroniclerV2Item<T>::validFrom)!!.data

suspend inline fun <reified T> HttpClient.chroniclerEntityMostRecentOrNull(
    chroniclerHost: String,
    type: String,
    at: String,
    builder: HttpRequestBuilder.() -> Unit = {},
): T? =
    get("$chroniclerHost/v2/entities") {
        parameter("type", type)
        parameter("at", at)

        builder()
    }.body<ChroniclerV2Data<T>>().items.maxByOrNull(ChroniclerV2Item<T>::validFrom)?.data


val SEASON_STREAM_QUERY_TIMES = arrayOf(
    "2020-07-26T00:00:00Z",
    "2020-08-02T00:00:00Z",
    "2020-08-09T00:00:00Z",
    "2020-08-30T00:00:00Z",
    "2020-09-06T00:00:00Z",
    "2020-09-13T00:00:00Z",
    "2020-09-20T00:00:00Z",
    "2020-09-27T00:00:00Z",
    "2020-10-11T00:00:00Z",
    "2020-10-18T00:00:00Z",
    "2020-10-25T00:00:00Z",
    "2021-03-07T00:00:00Z",
    "2021-03-14T00:00:00Z",
    "2021-03-21T00:00:00Z",
    "2021-04-11T00:00:00Z",
    "2021-04-18T00:00:00Z",
    "2021-04-25T00:00:00Z",
    "2021-05-16T00:00:00Z",
    "2021-05-23T00:00:00Z"
)

data class PlayerRequest(
    val players: List<String>,
    val time: String?,
    val backing: CompletableDeferred<Map<String, JsonObject>> = CompletableDeferred(),
) : CompletableDeferred<Map<String, JsonObject>> by backing

@Serializable
data class TimeMapWrapper(val data: List<TimeMap>)

@Serializable
data class TimeMap(
    val season: Int,
    val tournament: Int,
    val day: Int,
    val type: String?,
    val startTime: Instant,
    val endTime: Instant?,
) {
    operator fun contains(moment: Instant): Boolean = moment >= startTime && (endTime == null || moment < endTime)
}

@OptIn(ExperimentalStdlibApi::class, ObsoleteCoroutinesApi::class)
fun Application.setupConvenienceRoutes(httpClient: HttpClient, dataSources: BlaseballDataSource.Instances) {
    val chroniclerHost = "https://api.sibr.dev/chronicler"

    val siteFileRoutes = SiteFileRoutes(this, httpClient)

    val PLAYERS = actor<PlayerRequest> {
        val cache = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .build<Pair<String?, String>, JsonObject>()

        receiveAsFlow().onEach { request ->
            val map: MutableMap<String, JsonObject> = HashMap()

            request.players.forEach { id -> cache.getIfPresent(Pair(request.time, id))?.let { map[id] = it } }
            request.players.filterNot(map::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    if (request.time == null) {
                        httpClient.get("https://api.blaseball.com/database/players") {
                            parameter("ids", missing.joinToString(","))
                        }.body<List<JsonObject>>().associateByTo(map) { it.getString("id") }
                    } else {
                        httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "player", request.time) {
                            parameter("id", missing.joinToString(","))
                        }.associateByTo(map) { it.getString("id") }
                    }
                }
            }
            request.players.filterNot(map::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get("https://api.blaseball.com/database/players") {
                        parameter("ids", missing.joinToString(","))
                    }.body<List<JsonObject>>().associateByTo(map) { it.getString("id") }
                }
            }

            map.forEach { (k, v) -> cache.put(Pair(request.time, k), v) }

            request.complete(map)
        }.launchIn(this).join()
    }

    suspend fun PlayerRequest.request(): Map<String, JsonObject> {
        PLAYERS.send(this)
        return await()
    }

    val TIME_MAPS = Caffeine.newBuilder()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .buildLoadingKotlin<Unit, List<TimeMap>>(CorsMechanics) {
            httpClient.get("https://api.sibr.dev/chronicler/v1/time/map").body<TimeMapWrapper>().data
        }

    val TIME_MAPS_BY_SEASON = Caffeine.newBuilder()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .buildLoadingKotlin<Int, List<TimeMap>>(CorsMechanics) { key ->
            TIME_MAPS.await(Unit).filter { map -> map.season == key }
        }

    val TIME_MAPS_BY_SEASON_AND_DAY = Caffeine.newBuilder()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .buildLoadingKotlin<Int, List<TimeMap>>(CorsMechanics) { key ->
            val season = ((key shr 16) and 0xFF).toByte().toInt() //Hack for negatives
            val day = key and 0xFFFF
            TIME_MAPS_BY_SEASON.await(season).filter { map -> map.day == day }
        }

    val TIME_MAPS_BY_SEASON_DAY_AND_TOURNAMENT = Caffeine.newBuilder()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .buildLoadingKotlin<Int, List<TimeMap>>(CorsMechanics) { key ->
            val tournament = ((key shr 24) and 0xFF).toByte().toInt() //Hack for negatives
            TIME_MAPS_BY_SEASON_AND_DAY.await(key).filter { map -> map.tournament == tournament }
        }

    val TIME_MAPS_BY_TIME = Caffeine.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .maximumSize(500)
        .buildLoadingKotlin<String?, List<TimeMap>>(CorsMechanics) { key ->
            val time =
                if (key == null || key == "NOW") ClockKt.System.now()
                else runCatching { Instant.parse(key) }.getOrElse { ClockKt.System.now() }

            TIME_MAPS.await(Unit).filter { time in it }
        }

    val TEAMS = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildLoadingKotlin<Pair<String?, String>, JsonObject>(CorsMechanics) { (time: String?, id: String?) ->
            val team = httpClient.chroniclerEntityMostRecent<JsonObject>(
                chroniclerHost,
                "team",
                at = if (time == null || time == "NOW") ClockKt.System.now().toString() else time
            ) { parameter("id", id) }

            val playerIDs = buildList<String> {
                team.getJsonArrayOrNull("lineup")?.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(this::add) }
                team.getJsonArrayOrNull("shadows")?.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(this::add) }
                team.getJsonArrayOrNull("rotation")?.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(this::add) }

                team.getJsonArrayOrNull("bullpen")?.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(this::add) }
                team.getJsonArrayOrNull("bench")?.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(this::add) }
            }

            val players = PlayerRequest(playerIDs, time).request()

            return@buildLoadingKotlin buildJsonObject {
                team.forEach { (k, v) ->
                    when (k) {
                        "lineup", "shadows", "rotation", "bullpen", "bench" -> {
                            putJsonArray(k) {
                                (v as? JsonArray)?.forEach {
                                    it.jsonPrimitiveOrNull?.contentOrNull?.let(players::get)?.let(this::add)
                                }
                            }
                            put("${k}_ids", v)
                        }

                        else -> put(k, v)
                    }
                }
            }
        }

    val DIVISION_TEAMS = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, List<JsonObject>>(CorsMechanics) { time ->
            val time = if (time == "NOW") ClockKt.System.now().toString() else time

            val divisions = httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "division", at = time)
//            val teams = httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "team", at = time)
//                .associateBy { it.getString("id") }

            val teams: MutableMap<String, JsonObject> = HashMap()

            divisions.flatMap { it.getJsonArray("teams") }
                .mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
                .map { id -> launch { teams[id] = TEAMS[Pair(time, id)].await() } }
                .joinAll()

            divisions.map { division ->
                buildJsonObject {
                    putJsonArray("teams") {
                        division.getJsonArrayOrNull("teams")?.forEach { team ->
                            team.jsonPrimitiveOrNull?.contentOrNull?.let(teams::get)?.let(this::add)
                        }
                    }

                    division.forEach { (k, v) -> if (k != "teams") put(k, v) else put("team_ids", v) }
                }
            }
        }

    val SUBLEAGUE_TEAMS = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, List<JsonObject>>(CorsMechanics) { _time ->
            val time = if (_time == "NOW") ClockKt.System.now().toString() else _time

            val subleaguesDeferred =
                async { httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "subleague", at = time) }
            val divisionsDeferred = DIVISION_TEAMS[_time]

//            val divisions = httpClient.chroniclerEntityList<BlaseballDivision>(chroniclerHost, "division", at = time)
//                .associateBy(BlaseballDivision::id)
//            val teams = httpClient.chroniclerEntityList<ChroniclerBlaseballTeam>(chroniclerHost, "team", at = time)
//                .associateBy(ChroniclerBlaseballTeam::id)

            val subleagues = subleaguesDeferred.await()
            val divisions = divisionsDeferred.await().associateBy { it.getString("id") }

            subleagues.map { subleague ->
                buildJsonObject {
                    putJsonArray("divisions") {
                        subleague.getJsonArrayOrNull("divisions")?.forEach { division ->
                            division.jsonPrimitiveOrNull?.contentOrNull?.let(divisions::get)?.let(this::add)
                        }
                    }

                    subleague.forEach { (k, v) -> if (k != "divisions") put(k, v) else put("division_ids", v) }
                }
            }
        }

    val LEAGUE_TEAMS = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, List<JsonObject>>(CorsMechanics) { _time ->
            val time = if (_time == "NOW") ClockKt.System.now().toString() else _time

            val leaguesDeferred =
                async { httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "league", at = time) }
            val subleaguesDeferred = SUBLEAGUE_TEAMS[_time]
//            val divisionsDeferred = async {
//                httpClient.chroniclerEntityList<BlaseballDivision>(chroniclerHost, "division", at = time)
//                    .associateBy(BlaseballDivision::id)
//            }
//            val teamsDeferred = async {
//                httpClient.chroniclerEntityList<ChroniclerBlaseballTeam>(chroniclerHost, "team", at = time)
//                    .associateBy(ChroniclerBlaseballTeam::id)
//            }

            val tiebreakersDeferred = async {
                when (val element = httpClient.chroniclerEntityMostRecentOrNull<JsonElement>(
                    chroniclerHost,
                    "tiebreakers",
                    at = time
                )) {
                    is JsonArray -> Serialisation.json.decodeFromJsonElement<List<JsonObject>>(element)
                    null -> listOf()
                    else -> listOf(Serialisation.json.decodeFromJsonElement<JsonObject>(element))
                }
            }

            val leagues = leaguesDeferred.await()
            val subleagues = subleaguesDeferred.await().associateBy { it.getString("id") }
//            val divisions = divisionsDeferred.await()
//            val teams = teamsDeferred.await()
            val tiebreakers = tiebreakersDeferred.await()

            leagues.map { league ->
                buildJsonObject {
                    league.forEach { (k, v) ->
                        when (k) {
                            "subleagues" -> {
                                putJsonArray("subleagues") {
                                    league.getJsonArrayOrNull("subleagues")?.forEach { subleague ->
                                        subleague.jsonPrimitiveOrNull?.contentOrNull?.let(subleagues::get)
                                            ?.let(this::add)
                                    }
                                }
                                put("subleague_ids", v)
                            }

                            "tiebreakers" -> {
                                put("tiebreakers", tiebreakers.firstOrNull { it["id"] == v } ?: JsonNull)
                                put("tiebreakers_id", v)
                            }

                            else -> put(k, v)
                        }
                    }
                }
            }
        }

    val STREAM_BY_TIME = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, JsonObject>(CorsMechanics) { time ->
            httpClient.chroniclerEntityMostRecent<JsonObject>(
                chroniclerHost,
                "stream",
                at = if (time == "NOW") ClockKt.System.now().toString() else time
            )
        }

    val SIM_BY_TIME = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, JsonObject>(CorsMechanics) { time ->
            httpClient.chroniclerEntityMostRecent<JsonObject>(
                chroniclerHost,
                "sim",
                at = if (time == "NOW") ClockKt.System.now().toString() else time
            )
        }

    val GAME_STATSHEETS_BY_STAT_ID = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildLoadingKotlin<Pair<String?, String>, List<JsonObject>>(CorsMechanics) { (time: String?, id: String?) ->
            val gameStatsheets = if (time == null) {
                httpClient.get("https://api.blaseball.com/database/gameStatsheets") {
                    parameter("ids", id)
                }.body<List<JsonObject>>()
            } else {
                httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "gamestatsheet", time) {
                    parameter("id", id)
                }.ifEmpty {
                    httpClient.get("https://api.blaseball.com/database/gameStatsheets") {
                        parameter("ids", id)
                    }.body<List<JsonObject>>()
                }
            }

            val teamStatsheetIDs = buildList<String> {
                gameStatsheets.forEach { game ->
                    game.getStringOrNull("homeTeamStats")?.let(this::add)
                    game.getStringOrNull("awayTeamStats")?.let(this::add)
                }
            }

            val teamStatsheets = if (teamStatsheetIDs.isNotEmpty()) {
                if (time == null) {
                    httpClient.get("https://api.blaseball.com/database/teamStatsheets") {
                        parameter("ids", teamStatsheetIDs.joinToString(","))
                    }.body<List<JsonObject>>().associateByTo(HashMap()) { it.getString("id") }
                } else {
                    httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "teamstatsheet", time) {
                        parameter("id", teamStatsheetIDs.joinToString(","))
                    }.associateByTo(HashMap()) { it.getString("id") }
                }
            } else
                HashMap()

            teamStatsheetIDs.filterNot(teamStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get("https://api.blaseball.com/database/teamStatsheets") {
                        parameter("ids", missing.joinToString(","))
                    }.body<List<JsonObject>>().forEach { statsheet ->
                        teamStatsheets[statsheet.getString("id")] = statsheet
                    }
                }
            }

            val playerStatsheetIDs = teamStatsheets.values
                .flatMap { it.getJsonArray("playerStats") }
                .mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }

            val playerStatsheets = if (playerStatsheetIDs.isNotEmpty()) {
                if (time == null) {
                    httpClient.get("https://api.blaseball.com/database/playerStatsheets") {
                        parameter("ids", playerStatsheetIDs.joinToString(","))
                    }.body<List<JsonObject>>().associateByTo(HashMap()) { it.getString("id") }
                } else {
                    httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "playerstatsheet", time) {
                        parameter("id", playerStatsheetIDs.joinToString(","))
                    }.associateByTo(HashMap()) { it.getString("id") }
                }
            } else
                HashMap()

            playerStatsheetIDs.filterNot(playerStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get("https://api.blaseball.com/database/playerStatsheets") {
                        parameter("ids", missing.joinToString(","))
                    }.body<List<JsonObject>>().forEach { statsheet ->
                        playerStatsheets[statsheet.getString("id")] = statsheet
                    }
                }
            }

            return@buildLoadingKotlin gameStatsheets.map { gameStatsheet ->
                buildJsonObject {
                    gameStatsheet.forEach { (k, v) ->
                        if (!k.endsWith("TeamStats"))
                            put(k, v)
                        else {
                            put("${k}ID", v)

                            putJsonArray(k) {
                                teamStatsheets[v.jsonPrimitiveOrNull?.contentOrNull
                                    ?: return@putJsonArray]?.let { teamStatsheet ->
                                    addJsonObject {
                                        teamStatsheet.forEach { (k, v) ->
                                            if (k != "playerStats") put(k, v)
                                            else {
                                                put("playerStatIDs", v)
                                                putJsonArray("playerStats") {
                                                    v.jsonArrayOrNull?.forEach {
                                                        it.jsonPrimitiveOrNull?.content?.let(
                                                            playerStatsheets::get
                                                        )?.let(this::add)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    val GAME_STATSHEETS_BY_GAME_ID = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildLoadingKotlin<Pair<String?, String>, List<JsonObject>>(CorsMechanics) { (time: String?, id: String?) ->
            val game = httpClient.get("https://api.blaseball.com/database/gameById/$id").body<JsonObject>()
            GAME_STATSHEETS_BY_STAT_ID.await(time, game.getString("statsheet"))
        }

    val TEAM_STATSHEETS_BY_STAT_ID = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildLoadingKotlin<Pair<String?, String>, List<JsonObject>>(CorsMechanics) { (time, id) ->
            val teamStatsheets =
                if (time == null) {
                    httpClient.get("https://api.blaseball.com/database/teamStatsheets") {
                        parameter("ids", id)
                    }.body<List<JsonObject>>()
                } else {
                    httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "teamstatsheet", time) {
                        parameter("id", id)
                    }.ifEmpty {
                        httpClient.get("https://api.blaseball.com/database/teamStatsheets") {
                            parameter("ids", id)
                        }.body<List<JsonObject>>()
                    }
                }

            val playerStatsheetIDs = teamStatsheets.flatMap { it.getJsonArray("playerStats") }
                .mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            val playerStatsheets = if (playerStatsheetIDs.isNotEmpty()) {
                if (time == null) {
                    httpClient.get("https://api.blaseball.com/database/playerStatsheets") {
                        parameter("ids", playerStatsheetIDs.joinToString(","))
                    }.body<List<JsonObject>>().associateByTo(HashMap()) { it.getString("id") }
                } else {
                    httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "playerstatsheet", time) {
                        parameter("id", playerStatsheetIDs.joinToString(","))
                    }.associateByTo(HashMap()) { it.getString("id") }
                }
            } else
                HashMap()

            playerStatsheetIDs.filterNot(playerStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get("https://api.blaseball.com/database/playerStatsheets") {
                        parameter("ids", missing.joinToString(","))
                    }.body<List<JsonObject>>().forEach { statsheet ->
                        playerStatsheets[statsheet.getString("id")] = statsheet
                    }
                }
            }

            return@buildLoadingKotlin teamStatsheets.map { teamStatsheet ->
                buildJsonObject {
                    teamStatsheet.forEach { (k, v) ->
                        when (k) {
                            "playerStats" -> {
                                putJsonArray("playerStats") {
                                    v.jsonArrayOrNull?.forEach { id ->
                                        id.jsonPrimitiveOrNull
                                            ?.contentOrNull
                                            ?.let(playerStatsheets::get)
                                            ?.let(this::add)
                                    }
                                }
                                put("playerStatIDs", v)
                            }

                            else -> put(k, v)
                        }
                    }
                }
            }
        }

    val SEASON_STATSHEETS_BY_STAT_ID = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildLoadingKotlin<Pair<String?, String>, List<JsonObject>>(CorsMechanics) { (time, id) ->
            val seasonStatsheets = if (time == null) {
                httpClient.get("https://api.blaseball.com/database/seasonStatsheets") {
                    parameter("ids", id)
                }.body<List<JsonObject>>()
            } else {
                httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "seasonstatsheet", time) {
                    parameter("id", id)
                }.ifEmpty {
                    httpClient.get("https://api.blaseball.com/database/seasonStatsheets") {
                        parameter("ids", id)
                    }.body<List<JsonObject>>()
                }
            }

            val teamStatsheetIDs = seasonStatsheets.flatMap { it.getJsonArray("teamStats") }
                .mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            val teamStatsheets = buildJsonArray {
                teamStatsheetIDs.map { teamStatsheetID ->
                    launch {
                        TEAM_STATSHEETS_BY_STAT_ID[Pair(time, teamStatsheetID)]
                            .await()
                            .forEach { add(it) }
                    }
                }.joinAll()
            }

            return@buildLoadingKotlin seasonStatsheets.map { seasonStatsheet ->
                buildJsonObject {
                    seasonStatsheet.forEach { (k, v) ->
                        when (k) {
                            "teamStats" -> {
                                put("teamStats", teamStatsheets)
                                put("teamStatIDs", v)
                            }

                            else -> put(k, v)
                        }
                    }
                }
            }
        }

    val SEASON_BY_TIME = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, JsonObject>(CorsMechanics) { time ->
            val time = if (time == "NOW") ClockKt.System.now().toString() else time
            val seasonDataDeferred =
                async { httpClient.chroniclerEntityMostRecent<JsonObject>(chroniclerHost, "season", at = time) }
            val leagues = LEAGUE_TEAMS[time]

            val seasonData = seasonDataDeferred.await()

            val standings = async {
                httpClient.chroniclerEntityMostRecent<JsonObject>(chroniclerHost, "standings", at = time) {
                    parameter("id", seasonData.getString("standings"))
                }
            }

            val stats = SEASON_STATSHEETS_BY_STAT_ID[Pair(time, seasonData.getString("stats"))]

            return@buildLoadingKotlin buildJsonObject {
                seasonData.forEach { (k, v) ->
                    when (k) {
                        "league" -> {
                            put("league", leagues.await().firstOrNull { it["id"] == v } ?: JsonNull)
                            put("leagueID", v)
                        }

                        "standings" -> {
                            put("standings", standings.await())
                            put("standingsID", v)
                        }

                        "stats" -> {
                            put("stats", stats.await().firstOrNull { it["id"] == v } ?: JsonNull)
                            put("statsID", v)
                        }

                        else -> put(k, v)
                    }
                }
            }
        }

    val SEASON_QUERY_BY_NUMBER = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildLoadingKotlin<Int, String>(CorsMechanics) { season ->
            SEASON_STREAM_QUERY_TIMES.getOrNull(season)?.let { return@buildLoadingKotlin it }

            //So there's no direct way to get this
            //TODO: Build an endpoint for it
            //But, we can hack around it by calling *eventually* and getting the first event of a season
            return@buildLoadingKotlin httpClient.get("https://api.sibr.dev/eventually/v2/events?limit=1&season_min=${season - 1}&season_max=${season + 1}")
                .body<List<JsonObject>>()
                .first()
                .getString("created")
                .toInstant()
                .toString()
        }
    val SEASON_BY_NUMBER = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, JsonObject>(CorsMechanics) { season ->
            try {
                val seasonData = httpClient.get("https://api.blaseball.com/database/season") {
                    parameter("number", season)
                }.body<JsonObject>()

                val leagues =
                    LEAGUE_TEAMS[SEASON_QUERY_BY_NUMBER[seasonData.getJsonPrimitive("seasonNumber").int].await()]

                val standings = async {
                    httpClient.chroniclerEntityMostRecent<JsonObject>(
                        chroniclerHost,
                        "standings",
                        at = ClockKt.System.now().toString()
                    ) {
                        parameter("id", seasonData.getString("standings"))
                    }
                }

                val stats = SEASON_STATSHEETS_BY_STAT_ID[Pair(null, seasonData.getString("stats"))]


                return@buildLoadingKotlin buildJsonObject {
                    seasonData.forEach { (k, v) ->
                        when (k) {
                            "league" -> {
                                put("league", leagues.await().firstOrNull { it["id"] == v } ?: JsonNull)
                                put("leagueID", v)
                            }

                            "standings" -> {
                                put("standings", standings.await())
                                put("standingsID", v)
                            }

                            "stats" -> {
                                put("stats", stats.await().firstOrNull { it["id"] == v } ?: JsonNull)
                                put("statsID", v)
                            }

                            else -> put(k, v)
                        }
                    }
                }
            } catch (th: Throwable) {
                th.printStackTrace()
                throw th
            }
        }

    val SEASON_STATSHEETS_BY_SEASON = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, List<JsonObject>>(CorsMechanics) { season ->
            val seasonData = SEASON_BY_NUMBER[season].await()
            SEASON_STATSHEETS_BY_STAT_ID[null to seasonData.getString("statsID")].await()
        }

    val SEASON_STATSHEETS_BY_TIME = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, List<JsonObject>>(CorsMechanics) { time ->
            val seasonData = SEASON_BY_NUMBER[time].await()
            SEASON_STATSHEETS_BY_STAT_ID[null to seasonData.getString("statsID")].await()
        }

    val VAULT_OF_THE_RANGER = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, JsonObject>(CorsMechanics) { time ->
            val rawData = httpClient.get("https://api.blaseball.com/database/vault")
                .body<JsonObject>()

            val playerIDs =
                rawData.getJsonArray("legendaryPlayers").mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            val players = PlayerRequest(playerIDs, time).request()

            buildJsonObjectFrom(rawData) { k, v ->
                when (k) {
                    "legendaryPlayers" -> {
                        putJsonArray("legendaryPlayers") {
                            v.jsonArrayOrNull?.forEachString { players[it]?.let(this::add) }
                        }
                        put("legendaryPlayerIDs", v)

                        false
                    }

                    else -> true
                }
            }
        }

    val RISING_STARS = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildLoadingKotlin<String, JsonObject>(CorsMechanics) { time ->
            val rawData = httpClient.get("https://api.blaseball.com/api/getRisingStars")
                .body<JsonObject>()

            val playerIDs = rawData.getJsonArray("stars").mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            val players = PlayerRequest(playerIDs, time).request()

            buildJsonObjectFrom(rawData) { k, v ->
                when (k) {
                    "stars" -> {
                        putJsonArray("stars") {
                            v.jsonArrayOrNull?.forEachString { players[it]?.let(this::add) }
                        }
                        put("starIDs", v)

                        false
                    }

                    else -> true
                }
            }
        }

    routing {
        withJsonExplorer("/league_teams") {
            LEAGUE_TEAMS[request.queryParameters["at"] ?: "NOW"].await()
        }

        withJsonExplorer("/subleague_teams") {
            SUBLEAGUE_TEAMS[request.queryParameters["at"] ?: "NOW"].await()
        }

        withJsonExplorer("/division_teams") {
            DIVISION_TEAMS[request.queryParameters["at"] ?: "NOW"].await()
        }

        withJsonExplorer("/game_statsheets/by_stat_id/{id}") {
            GAME_STATSHEETS_BY_STAT_ID[(request.queryParameters["at"]) to parameters.getOrFail("id")].await()
        }

        withJsonExplorer("/game_statsheets/by_game_id/{id}") {
            GAME_STATSHEETS_BY_GAME_ID[(request.queryParameters["at"]) to parameters.getOrFail("id")].await()
        }

        withJsonExplorer("/team_statsheets/{id}") {
            TEAM_STATSHEETS_BY_STAT_ID[(request.queryParameters["at"]) to parameters.getOrFail("id")].await()
        }

        withJsonExplorer("/season_statsheets/by_stat_id/{id}") {
            SEASON_STATSHEETS_BY_STAT_ID[(request.queryParameters["at"]) to parameters.getOrFail("id")].await()
        }

        withJsonExplorer("/season_statsheets/by_season/{id}") {
            SEASON_STATSHEETS_BY_SEASON[parameters.getOrFail("id")].await()
        }

        withJsonExplorer("/season_statsheets/by_time") {
            SEASON_STATSHEETS_BY_TIME[request.queryParameters["at"] ?: "NOW"].await()
        }

        withJsonExplorer("/season/by_time") {
            SEASON_BY_TIME[request.queryParameters["at"] ?: "NOW"].await()
        }

        withJsonExplorer("/season/by_number/{id}") {
            SEASON_BY_NUMBER[parameters.getOrFail("id")].await()
        }

        withJsonExplorer("/time/season/{season}") {
            TIME_MAPS_BY_SEASON[parameters.getOrFail("season").toInt()].await()
        }

        withJsonExplorer("/time/season/{season}/day/{day}") {
            TIME_MAPS_BY_SEASON_AND_DAY[(parameters.getOrFail("season").toByte()
                .toInt() shl 16) or (parameters.getOrFail("day").toInt())].await()
        }

        withJsonExplorer("/time/season/{season}/day/{day}/tournament/{tournament}") {
            TIME_MAPS_BY_SEASON_DAY_AND_TOURNAMENT[
                (parameters.getOrFail("tournament").toByte().toInt() shl 24) or
                        (parameters.getOrFail("season").toByte().toInt() shl 16) or
                        (parameters.getOrFail("day").toInt())
            ].await()
        }

        withJsonExplorer("/time/within") {
            TIME_MAPS_BY_TIME[request.queryParameters["at"] ?: "NOW"].await()
        }

        get("/database/willResults") {

        }

        withJsonExplorer("/vault") {
            VAULT_OF_THE_RANGER[request.queryParameters["at"] ?: "NOW"].await()
        }

        withJsonExplorer("/rising_stars") {
            RISING_STARS[request.queryParameters["at"] ?: "NOW"].await()
        }

        if (DISABLE_EVENT_STREAM) {
            val stream = buildJsonObject {
                this.put(
                    "_comment",
                    "Event Stream temporarily disabled. Contact UnderMybrella#1084 if you need stream data at the moment"
                )
            }

            jsonExplorer("/stream") { stream }
        } else {
            jsonExplorer("/stream") {
                val dataSource = dataSources sourceFor this
                dataSource.eventStream!!.relaunchJobIfNeeded().join()
                dataSource.eventStream!!.liveData.first().getJsonObject("value")
            }
        }
    }
}