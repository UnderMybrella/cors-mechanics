package dev.brella.corsmechanics

import com.github.benmanes.caffeine.cache.Caffeine
import dev.brella.kornea.blaseball.base.common.*
import dev.brella.kornea.blaseball.base.common.beans.*
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

@Serializable
data class ConvenienceLeague(
    val id: LeagueID,
    val subleagues: List<ConvenienceSubLeague>,
    val name: String,
    val tiebreakers: List<BlaseballTiebreaker>,
    val tiebreakersID: TiebreakerID
)

@Serializable
data class ConvenienceSubLeague(
    val id: SubleagueID,
    val divisions: List<ConvenienceDivision>,
    val name: String
)

@Serializable
data class ConvenienceDivision(
    val id: DivisionID,
    val teams: List<ChroniclerBlaseballTeam>,
    val name: String
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
    val level: Int? = null
)

@Serializable
data class ChroniclerV2Data<T>(val nextPage: String? = null, val items: List<ChroniclerV2Item<T>> = emptyList())

@Serializable
data class ChroniclerV2Item<T>(val entityId: String, val hash: String, val validFrom: Instant, val validTo: Instant?, val data: T)

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
    val homeTeamStatsheetID: TeamStatsheetID
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
    val teamId: TeamID
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
    val pitchesThrown: Int? = null
)

@Serializable
data class ConvenienceSeasonStatsheet(
    val id: SeasonStatsheetID,
    val teamStats: List<ConvenienceTeamStatsheet> = emptyList(),
    val teamStatIDs: List<TeamStatsheetID>
)

@Serializable
data class BlaseballGameStatsheet(
    val id: GameStatsheetID,
    val homeTeamRunsByInning: List<Double>,
    val awayTeamRunsByInning: List<Double>,
    val awayTeamTotalBatters: Int,
    val homeTeamTotalBatters: Int,
    val awayTeamStats: TeamStatsheetID,
    val homeTeamStats: TeamStatsheetID
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
    val terminology: TerminologyID
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
    val terminology: TerminologyID
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
    val pitchesThrown: Int? = null
)

suspend inline fun <reified T> HttpClient.chroniclerVersionMostRecent(chroniclerHost: String, type: String, builder: HttpRequestBuilder.() -> Unit = {}): T =
    get<ChroniclerV2Data<T>>("$chroniclerHost/v2/entities") {
        parameter("type", type)

        builder()
    }.items.first().data

suspend inline fun <reified T> HttpClient.chroniclerVersionList(chroniclerHost: String, type: String, builder: HttpRequestBuilder.() -> Unit = {}): List<T> =
    get<ChroniclerV2Data<T>>("$chroniclerHost/v2/entities") {
        parameter("type", type)

        builder()
    }.items.map(ChroniclerV2Item<T>::data)

suspend inline fun <reified T> HttpClient.chroniclerVersionListItems(chroniclerHost: String, type: String, builder: HttpRequestBuilder.() -> Unit = {}): List<ChroniclerV2Item<T>> =
    get<ChroniclerV2Data<T>>("$chroniclerHost/v2/entities") {
        parameter("type", type)

        builder()
    }.items

suspend inline fun <reified T> HttpClient.chroniclerEntityList(chroniclerHost: String, type: String, at: String, builder: HttpRequestBuilder.() -> Unit = {}): List<T> =
    get<ChroniclerV2Data<T>>("$chroniclerHost/v2/entities") {
        parameter("type", type)
        parameter("at", at)

        builder()
    }.items.map(ChroniclerV2Item<T>::data)

suspend inline fun <reified T> HttpClient.chroniclerEntityMostRecent(chroniclerHost: String, type: String, at: String, builder: HttpRequestBuilder.() -> Unit = {}): T =
    get<ChroniclerV2Data<T>>("$chroniclerHost/v2/entities") {
        parameter("type", type)
        parameter("at", at)

        builder()
    }.items.maxByOrNull(ChroniclerV2Item<T>::validFrom)!!.data

suspend inline fun <reified T> HttpClient.chroniclerEntityMostRecentOrNull(chroniclerHost: String, type: String, at: String, builder: HttpRequestBuilder.() -> Unit = {}): T? =
    get<ChroniclerV2Data<T>>("$chroniclerHost/v2/entities") {
        parameter("type", type)
        parameter("at", at)

        builder()
    }.items.maxByOrNull(ChroniclerV2Item<T>::validFrom)?.data


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

data class PlayerRequest(val players: List<String>, val time: String?, val backing: CompletableDeferred<Map<String, JsonObject>> = CompletableDeferred()): CompletableDeferred<Map<String, JsonObject>> by backing

@OptIn(ExperimentalStdlibApi::class)
fun Application.setupConvenienceRoutes(httpClient: HttpClient, liveData: LiveData, liveDataStringFlow: SharedFlow<String>) {
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
                        httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/players") {
                            parameter("ids", missing.joinToString(","))
                        }.associateByTo(map) { it.getString("id") }
                    } else {
                        httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "player", request.time) {
                            parameter("id", missing.joinToString(","))
                        }.associateByTo(map) { it.getString("id") }
                    }
                }
            }
            request.players.filterNot(map::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/players") {
                        parameter("ids", missing.joinToString(","))
                    }.associateByTo(map) { it.getString("id") }
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

    val TEAMS = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<Pair<String?, String>, JsonObject> { (time, id) ->
            val team = httpClient.chroniclerEntityMostRecent<JsonObject>(chroniclerHost, "team", at = if (time == null || time == "NOW") Clock.System.now().toString() else time) {
                parameter("id", id)
            }

            val playerIDs = buildList<String> {
                team.getJsonArrayOrNull("lineup")?.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(this::add) }
                team.getJsonArrayOrNull("shadows")?.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(this::add) }
                team.getJsonArrayOrNull("rotation")?.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(this::add) }

                team.getJsonArrayOrNull("bullpen")?.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(this::add) }
                team.getJsonArrayOrNull("bench")?.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let(this::add) }
            }

            val players = PlayerRequest(playerIDs, time).request()

            return@buildCoroutines buildJsonObject {
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
        .buildCoroutines<String, List<JsonObject>> { time ->
            val time = if (time == "NOW") Clock.System.now().toString() else time

            val divisions = httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "division", at = time)
//            val teams = httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "team", at = time)
//                .associateBy { it.getString("id") }

            val teams: MutableMap<String, JsonObject> = HashMap()

            coroutineScope {
                divisions.flatMap { it.getJsonArray("teams") }
                    .mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
                    .map { id -> launch { teams[id] = TEAMS[Pair(time, id)].await() } }
                    .joinAll()
            }

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
        .buildCoroutines<String, List<JsonObject>> { _time ->
            val time = if (_time == "NOW") Clock.System.now().toString() else _time

            val subleaguesDeferred = async { httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "subleague", at = time) }
            val divisionsDeferred = DIVISION_TEAMS[_time].asDeferred()

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
        .buildCoroutines<String, List<JsonObject>> { _time ->
            val time = if (_time == "NOW") Clock.System.now().toString() else _time

            val leaguesDeferred = async { httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "league", at = time) }
            val subleaguesDeferred = SUBLEAGUE_TEAMS[_time].asDeferred()
//            val divisionsDeferred = async {
//                httpClient.chroniclerEntityList<BlaseballDivision>(chroniclerHost, "division", at = time)
//                    .associateBy(BlaseballDivision::id)
//            }
//            val teamsDeferred = async {
//                httpClient.chroniclerEntityList<ChroniclerBlaseballTeam>(chroniclerHost, "team", at = time)
//                    .associateBy(ChroniclerBlaseballTeam::id)
//            }

            val tiebreakersDeferred = async {
                when (val element = httpClient.chroniclerEntityMostRecentOrNull<JsonElement>(chroniclerHost, "tiebreakers", at = time)) {
                    is JsonArray -> json.decodeFromJsonElement<List<JsonObject>>(element)
                    null -> listOf()
                    else -> listOf(json.decodeFromJsonElement<JsonObject>(element))
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
                                        subleague.jsonPrimitiveOrNull?.contentOrNull?.let(subleagues::get)?.let(this::add)
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
        .buildCoroutines<String, JsonObject> { time ->
            httpClient.chroniclerEntityMostRecent<JsonObject>(chroniclerHost, "stream", at = if (time == "NOW") Clock.System.now().toString() else time)
        }

    val SIM_BY_TIME = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<String, JsonObject> { time ->
            httpClient.chroniclerEntityMostRecent<JsonObject>(chroniclerHost, "sim", at = if (time == "NOW") Clock.System.now().toString() else time)
        }

    val GAME_STATSHEETS_BY_STAT_ID = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildCoroutines<Pair<String?, String>, List<JsonObject>> { (time, id) ->
            val gameStatsheets = if (time == null) {
                httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/gameStatsheets") {
                    parameter("ids", id)
                }
            } else {
                httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "gamestatsheet", time) {
                    parameter("id", id)
                }.ifEmpty {
                    httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/gameStatsheets") {
                        parameter("ids", id)
                    }
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
                    httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/teamStatsheets") {
                        parameter("ids", teamStatsheetIDs.joinToString(","))
                    }.associateByTo(HashMap()) { it.getString("id") }
                } else {
                    httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "teamstatsheet", time) {
                        parameter("id", teamStatsheetIDs.joinToString(","))
                    }.associateByTo(HashMap()) { it.getString("id") }
                }
            } else
                HashMap()

            teamStatsheetIDs.filterNot(teamStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/teamStatsheets") {
                        parameter("ids", missing.joinToString(","))
                    }.forEach { statsheet ->
                        teamStatsheets[statsheet.getString("id")] = statsheet
                    }
                }
            }

            val playerStatsheetIDs = teamStatsheets.values
                .flatMap { it.getJsonArray("playerStats") }
                .mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }

            val playerStatsheets = if (playerStatsheetIDs.isNotEmpty()) {
                if (time == null) {
                    httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/playerStatsheets") {
                        parameter("ids", playerStatsheetIDs.joinToString(","))
                    }.associateByTo(HashMap()) { it.getString("id") }
                } else {
                    httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "playerstatsheet", time) {
                        parameter("id", playerStatsheetIDs.joinToString(","))
                    }.associateByTo(HashMap()) { it.getString("id") }
                }
            } else
                HashMap()

            playerStatsheetIDs.filterNot(playerStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/playerStatsheets") {
                        parameter("ids", missing.joinToString(","))
                    }.forEach { statsheet ->
                        playerStatsheets[statsheet.getString("id")] = statsheet
                    }
                }
            }

            return@buildCoroutines gameStatsheets.map { gameStatsheet ->
                buildJsonObject {
                    gameStatsheet.forEach { (k, v) ->
                        if (!k.endsWith("TeamStats"))
                            put(k, v)
                        else {
                            put("${k}ID", v)

                            putJsonArray(k) {
                                teamStatsheets[v.jsonPrimitiveOrNull?.contentOrNull ?: return@putJsonArray]?.let { teamStatsheet ->
                                    addJsonObject {
                                        teamStatsheet.forEach { (k, v) ->
                                            if (k != "playerStats") put(k, v)
                                            else {
                                                put("playerStatIDs", v)
                                                putJsonArray("playerStats") {
                                                    v.jsonArrayOrNull?.forEach { it.jsonPrimitiveOrNull?.content?.let(playerStatsheets::get)?.let(this::add) }
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
        .buildCoroutines<Pair<String?, String>, List<JsonObject>> { (time, id) ->
            val game = httpClient.get<JsonObject>("https://www.blaseball.com/database/gameById/$id")
            GAME_STATSHEETS_BY_STAT_ID[Pair(time, game.getString("statsheet"))].await()
        }

    val TEAM_STATSHEETS_BY_STAT_ID = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildCoroutines<Pair<String?, String>, List<JsonObject>> { (time, id) ->
            val teamStatsheets =
                if (time == null) {
                    httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/teamStatsheets") {
                        parameter("ids", id)
                    }
                } else {
                    httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "teamstatsheet", time) {
                        parameter("id", id)
                    }.ifEmpty {
                        httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/teamStatsheets") {
                            parameter("ids", id)
                        }
                    }
                }

            val playerStatsheetIDs = teamStatsheets.flatMap { it.getJsonArray("playerStats") }.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            val playerStatsheets = if (playerStatsheetIDs.isNotEmpty()) {
                if (time == null) {
                    httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/playerStatsheets") {
                        parameter("ids", playerStatsheetIDs.joinToString(","))
                    }.associateByTo(HashMap()) { it.getString("id") }
                } else {
                    httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "playerstatsheet", time) {
                        parameter("id", playerStatsheetIDs.joinToString(","))
                    }.associateByTo(HashMap()) { it.getString("id") }
                }
            } else
                HashMap()

            playerStatsheetIDs.filterNot(playerStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/playerStatsheets") {
                        parameter("ids", missing.joinToString(","))
                    }.forEach { statsheet ->
                        playerStatsheets[statsheet.getString("id")] = statsheet
                    }
                }
            }

            return@buildCoroutines teamStatsheets.map { teamStatsheet ->
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
        .buildCoroutines<Pair<String?, String>, List<JsonObject>> { (time, id) ->
            val seasonStatsheets = if (time == null) {
                httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/seasonStatsheets") {
                    parameter("ids", id)
                }
            } else {
                httpClient.chroniclerEntityList<JsonObject>(chroniclerHost, "seasonstatsheet", time) {
                    parameter("id", id)
                }.ifEmpty {
                    httpClient.get<List<JsonObject>>("https://www.blaseball.com/database/seasonStatsheets") {
                        parameter("ids", id)
                    }
                }
            }

            val teamStatsheetIDs = seasonStatsheets.flatMap { it.getJsonArray("teamStats") }.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            val teamStatsheets = buildJsonArray {
                teamStatsheetIDs.map { teamStatsheetID ->
                    launch {
                        TEAM_STATSHEETS_BY_STAT_ID[Pair(time, teamStatsheetID)]
                            .await()
                            .forEach { add(it) }
                    }
                }.joinAll()
            }

            return@buildCoroutines seasonStatsheets.map { seasonStatsheet ->
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
        .buildCoroutines<String, JsonObject> { time ->
            val time = if (time == "NOW") Clock.System.now().toString() else time
            val seasonDataDeferred = async { httpClient.chroniclerEntityMostRecent<JsonObject>(chroniclerHost, "season", at = time) }
            val leagues = LEAGUE_TEAMS[time].asDeferred()

            val seasonData = seasonDataDeferred.await()

            val standings = async {
                httpClient.chroniclerEntityMostRecent<JsonObject>(chroniclerHost, "standings", at = time) {
                    parameter("id", seasonData.getString("standings"))
                }
            }

            val stats = SEASON_STATSHEETS_BY_STAT_ID[Pair(time, seasonData.getString("stats"))].asDeferred()

            return@buildCoroutines buildJsonObject {
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
        .buildCoroutines<Int, String> { season ->
            SEASON_STREAM_QUERY_TIMES.getOrNull(season)?.let { return@buildCoroutines it }

            //So there's no direct way to get this
            //TODO: Build an endpoint for it
            //But, we can hack around it by calling *eventually* and getting the first event of a season
            return@buildCoroutines httpClient.get<List<JsonObject>>("https://api.sibr.dev/eventually/v2/events?limit=1&season_min=${season - 1}&season_max=${season + 1}")
                .first()
                .getString("created")
                .toInstant()
                .toString()
        }
    val SEASON_BY_NUMBER = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<String, JsonObject> { season ->
            try {
                val seasonData = httpClient.get<JsonObject>("https://www.blaseball.com/database/season") {
                    parameter("number", season)
                }

                val leagues = LEAGUE_TEAMS[SEASON_QUERY_BY_NUMBER[seasonData.getJsonPrimitive("seasonNumber").int].await()].asDeferred()

                val standings = async {
                    httpClient.chroniclerEntityMostRecent<JsonObject>(chroniclerHost, "standings", at = Clock.System.now().toString()) {
                        parameter("id", seasonData.getString("standings"))
                    }
                }

                val stats = SEASON_STATSHEETS_BY_STAT_ID[Pair(null, seasonData.getString("stats"))].asDeferred()


                return@buildCoroutines buildJsonObject {
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
        .buildAsync<String, List<JsonObject>> { season, _ ->
            SEASON_BY_NUMBER[season].thenCompose { seasonData ->
                SEASON_STATSHEETS_BY_STAT_ID[null to seasonData.getString("statsID")]
            }
        }

    val SEASON_STATSHEETS_BY_TIME = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildAsync<String, List<JsonObject>> { time, _ ->
            SEASON_BY_TIME[time].thenCompose { seasonData ->
                SEASON_STATSHEETS_BY_STAT_ID[null to seasonData.getString("statsID")]
            }
        }

    val VAULT_OF_THE_RANGER = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildCoroutines<String, JsonObject> { time ->
            val rawData = httpClient.get<JsonObject>("https://www.blaseball.com/database/vault")

            val playerIDs = rawData.getJsonArray("legendaryPlayers").mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
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
        .buildCoroutines<String, JsonObject> { time ->
            val rawData = httpClient.get<JsonObject>("https://www.blaseball.com/api/getRisingStars")

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

        get("/database/willResults") {

        }

        withJsonExplorer("/vault") {
            VAULT_OF_THE_RANGER[request.queryParameters["at"] ?: "NOW"].await()
        }

        withJsonExplorer("/rising_stars") {
            RISING_STARS[request.queryParameters["at"] ?: "NOW"].await()
        }

        val liveDataFlow = liveData.liveData

        jsonExplorer("/stream") {
            if (liveData.updateJob?.isActive != true) liveData.relaunchJob()

            liveDataFlow.first().getJsonObject("value")
        }
    }
}