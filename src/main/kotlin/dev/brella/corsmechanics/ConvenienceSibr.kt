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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
data class ChroniclerV2Data<T>(val nextPage: String? = null, val items: List<ChroniclerV2Item<T>>)

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

@OptIn(ExperimentalStdlibApi::class)
fun Application.setupConvenienceRoutes(httpClient: HttpClient, liveData: LiveData, liveDataStringFlow: SharedFlow<String>) {
    val chroniclerHost = "https://api.sibr.dev/chronicler"

    val siteFileRoutes = SiteFileRoutes(this, httpClient)

    val DIVISION_TEAMS = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<String, List<ConvenienceDivision>> { time ->
            val time = if (time == "NOW") Clock.System.now().toString() else time

            val divisions = httpClient.chroniclerEntityList<BlaseballDivision>(chroniclerHost, "division", at = time)
            val teams = httpClient.chroniclerEntityList<ChroniclerBlaseballTeam>(chroniclerHost, "team", at = time)
                .associateBy(ChroniclerBlaseballTeam::id)


            divisions.map { division ->
                ConvenienceDivision(
                    division.id,
                    division.teams.mapNotNull(teams::get),
                    division.name
                )
            }
        }

    val SUBLEAGUE_TEAMS = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<String, List<ConvenienceSubLeague>> { _time ->
            val time = if (_time == "NOW") Clock.System.now().toString() else _time

            val subleaguesDeferred = async { httpClient.chroniclerEntityList<BlaseballSubleague>(chroniclerHost, "subleague", at = time) }
            val divisionsDeferred = DIVISION_TEAMS[_time].asDeferred()

//            val divisions = httpClient.chroniclerEntityList<BlaseballDivision>(chroniclerHost, "division", at = time)
//                .associateBy(BlaseballDivision::id)
//            val teams = httpClient.chroniclerEntityList<ChroniclerBlaseballTeam>(chroniclerHost, "team", at = time)
//                .associateBy(ChroniclerBlaseballTeam::id)

            val subleagues = subleaguesDeferred.await()
            val divisions = divisionsDeferred.await().associateBy(ConvenienceDivision::id)

            subleagues.map { subleague ->
                ConvenienceSubLeague(
                    subleague.id,
                    subleague.divisions.mapNotNull(divisions::get),
                    subleague.name
                )
            }
        }

    val LEAGUE_TEAMS = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<String, List<ConvenienceLeague>> { _time ->
            val time = if (_time == "NOW") Clock.System.now().toString() else _time

            val leaguesDeferred = async { httpClient.chroniclerEntityList<BlaseballLeague>(chroniclerHost, "league", at = time) }
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
                    is JsonArray -> json.decodeFromJsonElement<List<BlaseballTiebreaker>>(element)
                    null -> listOf()
                    else -> listOf(json.decodeFromJsonElement<BlaseballTiebreaker>(element))
                }
            }

            val leagues = leaguesDeferred.await()
            val subleagues = subleaguesDeferred.await().associateBy(ConvenienceSubLeague::id)
//            val divisions = divisionsDeferred.await()
//            val teams = teamsDeferred.await()
            val tiebreakers = tiebreakersDeferred.await()

            leagues.map { league ->
                ConvenienceLeague(
                    league.id,
                    league.subleagues.mapNotNull(subleagues::get),
                    league.name,
                    tiebreakers,
                    TiebreakerID(league.tiebreakers)
                )
            }
        }

    val STREAM_BY_TIME = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<String, BlaseballStreamDataResponse> { time ->
            httpClient.chroniclerEntityMostRecent<BlaseballStreamDataResponse>(chroniclerHost, "stream", at = if (time == "NOW") Clock.System.now().toString() else time)
        }

    val SIM_BY_TIME = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<String, BlaseballSimulationData> { time ->
            httpClient.chroniclerEntityMostRecent<BlaseballSimulationData>(chroniclerHost, "sim", at = if (time == "NOW") Clock.System.now().toString() else time)
        }

    val GAME_STATSHEETS_BY_STAT_ID = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildCoroutines<Pair<String?, String>, List<ConvenienceGameStatsheet>> { (time, id) ->
            val gameStatsheets = if (time == null) {
                httpClient.get<List<BlaseballGameStatsheet>>("https://www.blaseball.com/database/gameStatsheets") {
                    parameter("ids", id)
                }
            } else {
                httpClient.chroniclerEntityList<BlaseballGameStatsheet>(chroniclerHost, "gamestatsheet", time) {
                    parameter("id", id)
                }.ifEmpty {
                    httpClient.get<List<BlaseballGameStatsheet>>("https://www.blaseball.com/database/gameStatsheets") {
                        parameter("ids", id)
                    }
                }
            }

            val teamStatsheetIDs = buildList<TeamStatsheetID> {
                gameStatsheets.forEach { game ->
                    add(game.homeTeamStats)
                    add(game.awayTeamStats)
                }
            }

            val teamStatsheets = if (teamStatsheetIDs.isNotEmpty()) {
                if (time == null) {
                    httpClient.get<List<BlaseballTeamStatsheet>>("https://www.blaseball.com/database/teamStatsheets") {
                        parameter("ids", teamStatsheetIDs.joinParams())
                    }.associateByTo(HashMap(), BlaseballTeamStatsheet::id)
                } else {
                    httpClient.chroniclerEntityList<BlaseballTeamStatsheet>(chroniclerHost, "teamstatsheet", time) {
                        parameter("id", teamStatsheetIDs.joinParams())
                    }.associateByTo(HashMap(), BlaseballTeamStatsheet::id)
                }
            } else
                HashMap()

            teamStatsheetIDs.filterNot(teamStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get<List<BlaseballTeamStatsheet>>("https://www.blaseball.com/database/teamStatsheets") {
                        parameter("ids", missing.joinParams())
                    }.forEach { statsheet ->
                        teamStatsheets[statsheet.id] = statsheet
                    }
                }
            }

            val playerStatsheetIDs = teamStatsheets.values.flatMap(BlaseballTeamStatsheet::playerStats)
            val playerStatsheets = if (playerStatsheetIDs.isNotEmpty()) {
                if (time == null) {
                    httpClient.get<List<BlaseballPlayerStatsheet>>("https://www.blaseball.com/database/playerStatsheets") {
                        parameter("ids", playerStatsheetIDs.joinParams())
                    }.associateByTo(HashMap(), BlaseballPlayerStatsheet::id)
                } else {
                    httpClient.chroniclerEntityList<BlaseballPlayerStatsheet>(chroniclerHost, "playerstatsheet", time) {
                        parameter("id", playerStatsheetIDs.joinParams())
                    }.associateByTo(HashMap(), BlaseballPlayerStatsheet::id)
                }
            } else
                HashMap()

            playerStatsheetIDs.filterNot(playerStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get<List<BlaseballPlayerStatsheet>>("https://www.blaseball.com/database/playerStatsheets") {
                        parameter("ids", missing.joinParams())
                    }.forEach { statsheet ->
                        playerStatsheets[statsheet.id] = statsheet
                    }
                }
            }

            return@buildCoroutines gameStatsheets.map { gameStatsheet ->
                ConvenienceGameStatsheet(
                    gameStatsheet.id,
                    gameStatsheet.homeTeamRunsByInning,
                    gameStatsheet.awayTeamRunsByInning,
                    gameStatsheet.awayTeamTotalBatters,
                    gameStatsheet.homeTeamTotalBatters,
                    teamStatsheets[gameStatsheet.awayTeamStats]?.let { teamStatsheet ->
                        ConvenienceTeamStatsheet(
                            teamStatsheet.id,
                            teamStatsheet.playerStats.mapNotNull(playerStatsheets::get).map { playerStatsheet ->
                                ConveniencePlayerStatsheet(
                                    playerStatsheet.id,
                                    playerStatsheet.playerId,
                                    playerStatsheet.teamId,
                                    playerStatsheet.team,
                                    playerStatsheet.name,
                                    playerStatsheet.atBats,
                                    playerStatsheet.caughtStealing,
                                    playerStatsheet.doubles,
                                    playerStatsheet.earnedRuns,
                                    playerStatsheet.groundIntoDp,
                                    playerStatsheet.hits,
                                    playerStatsheet.hitsAllowed,
                                    playerStatsheet.homeRuns,
                                    playerStatsheet.losses,
                                    playerStatsheet.outsRecorded,
                                    playerStatsheet.rbis,
                                    playerStatsheet.homeRuns,
                                    playerStatsheet.stolenBases,
                                    playerStatsheet.strikeouts,
                                    playerStatsheet.struckouts,
                                    playerStatsheet.triples,
                                    playerStatsheet.walks,
                                    playerStatsheet.walksIssued,
                                    playerStatsheet.wins,
                                    playerStatsheet.hitByPitch,
                                    playerStatsheet.hitBatters,
                                    playerStatsheet.quadruples,
                                    playerStatsheet.pitchesThrown,
                                )
                            },
                            teamStatsheet.playerStats,
                            teamStatsheet.gamesPlayed,
                            teamStatsheet.wins,
                            teamStatsheet.losses,
                            teamStatsheet.name,
                            teamStatsheet.teamId
                        )
                    },
                    teamStatsheets[gameStatsheet.homeTeamStats]?.let { teamStatsheet ->
                        ConvenienceTeamStatsheet(
                            teamStatsheet.id,
                            teamStatsheet.playerStats.mapNotNull(playerStatsheets::get).map { playerStatsheet ->
                                ConveniencePlayerStatsheet(
                                    playerStatsheet.id,
                                    playerStatsheet.playerId,
                                    playerStatsheet.teamId,
                                    playerStatsheet.team,
                                    playerStatsheet.name,
                                    playerStatsheet.atBats,
                                    playerStatsheet.caughtStealing,
                                    playerStatsheet.doubles,
                                    playerStatsheet.earnedRuns,
                                    playerStatsheet.groundIntoDp,
                                    playerStatsheet.hits,
                                    playerStatsheet.hitsAllowed,
                                    playerStatsheet.homeRuns,
                                    playerStatsheet.losses,
                                    playerStatsheet.outsRecorded,
                                    playerStatsheet.rbis,
                                    playerStatsheet.homeRuns,
                                    playerStatsheet.stolenBases,
                                    playerStatsheet.strikeouts,
                                    playerStatsheet.struckouts,
                                    playerStatsheet.triples,
                                    playerStatsheet.walks,
                                    playerStatsheet.walksIssued,
                                    playerStatsheet.wins,
                                    playerStatsheet.hitByPitch,
                                    playerStatsheet.hitBatters,
                                    playerStatsheet.quadruples,
                                    playerStatsheet.pitchesThrown,
                                )
                            },
                            teamStatsheet.playerStats,
                            teamStatsheet.gamesPlayed,
                            teamStatsheet.wins,
                            teamStatsheet.losses,
                            teamStatsheet.name,
                            teamStatsheet.teamId
                        )
                    },
                    gameStatsheet.awayTeamStats,
                    gameStatsheet.homeTeamStats
                )
            }
        }

    val GAME_STATSHEETS_BY_GAME_ID = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildCoroutines<Pair<String?, String>, List<ConvenienceGameStatsheet>> { (time, id) ->
            val game = httpClient.get<BlaseballDatabaseGame>("https://www.blaseball.com/database/gameById/$id")
            GAME_STATSHEETS_BY_STAT_ID[Pair(time, game.statsheet.id)].await()
        }

    val TEAM_STATSHEETS_BY_STAT_ID = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildCoroutines<Pair<String?, String>, List<ConvenienceTeamStatsheet>> { (time, id) ->
            val teamStatsheets =
                if (time == null) {
                    httpClient.get<List<BlaseballTeamStatsheet>>("https://www.blaseball.com/database/teamStatsheets") {
                        parameter("ids", id)
                    }
                } else {
                    httpClient.chroniclerEntityList<BlaseballTeamStatsheet>(chroniclerHost, "teamstatsheet", time) {
                        parameter("id", id)
                    }.ifEmpty {
                        httpClient.get<List<BlaseballTeamStatsheet>>("https://www.blaseball.com/database/teamStatsheets") {
                            parameter("ids", id)
                        }
                    }
                }

            val playerStatsheetIDs = teamStatsheets.flatMap(BlaseballTeamStatsheet::playerStats)
            val playerStatsheets = if (playerStatsheetIDs.isNotEmpty()) {
                if (time == null) {
                    httpClient.get<List<BlaseballPlayerStatsheet>>("https://www.blaseball.com/database/playerStatsheets") {
                        parameter("ids", playerStatsheetIDs.joinParams())
                    }.associateByTo(HashMap(), BlaseballPlayerStatsheet::id)
                } else {
                    httpClient.chroniclerEntityList<BlaseballPlayerStatsheet>(chroniclerHost, "playerstatsheet", time) {
                        parameter("id", playerStatsheetIDs.joinParams())
                    }.associateByTo(HashMap(), BlaseballPlayerStatsheet::id)
                }
            } else
                HashMap()

            playerStatsheetIDs.filterNot(playerStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    httpClient.get<List<BlaseballPlayerStatsheet>>("https://www.blaseball.com/database/playerStatsheets") {
                        parameter("ids", missing.joinParams())
                    }.forEach { statsheet ->
                        playerStatsheets[statsheet.id] = statsheet
                    }
                }
            }

            return@buildCoroutines teamStatsheets.map { teamStatsheet ->
                ConvenienceTeamStatsheet(
                    teamStatsheet.id,
                    teamStatsheet.playerStats.mapNotNull(playerStatsheets::get).map { playerStatsheet ->
                        ConveniencePlayerStatsheet(
                            playerStatsheet.id,
                            playerStatsheet.playerId,
                            playerStatsheet.teamId,
                            playerStatsheet.team,
                            playerStatsheet.name,
                            playerStatsheet.atBats,
                            playerStatsheet.caughtStealing,
                            playerStatsheet.doubles,
                            playerStatsheet.earnedRuns,
                            playerStatsheet.groundIntoDp,
                            playerStatsheet.hits,
                            playerStatsheet.hitsAllowed,
                            playerStatsheet.homeRuns,
                            playerStatsheet.losses,
                            playerStatsheet.outsRecorded,
                            playerStatsheet.rbis,
                            playerStatsheet.homeRuns,
                            playerStatsheet.stolenBases,
                            playerStatsheet.strikeouts,
                            playerStatsheet.struckouts,
                            playerStatsheet.triples,
                            playerStatsheet.walks,
                            playerStatsheet.walksIssued,
                            playerStatsheet.wins,
                            playerStatsheet.hitByPitch,
                            playerStatsheet.hitBatters,
                            playerStatsheet.quadruples,
                            playerStatsheet.pitchesThrown,
                        )
                    },
                    teamStatsheet.playerStats,
                    teamStatsheet.gamesPlayed,
                    teamStatsheet.wins,
                    teamStatsheet.losses,
                    teamStatsheet.name,
                    teamStatsheet.teamId
                )
            }
        }

    val SEASON_STATSHEETS_BY_STAT_ID = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildCoroutines<Pair<String?, String>, List<ConvenienceSeasonStatsheet>> { (time, id) ->
            val seasonStatsheets = if (time == null) {
                httpClient.get<List<BlaseballSeasonStatsheet>>("https://www.blaseball.com/database/seasonStatsheets") {
                    parameter("ids", id)
                }
            } else {
                httpClient.chroniclerEntityList<BlaseballSeasonStatsheet>(chroniclerHost, "seasonstatsheet", time) {
                    parameter("id", id)
                }.ifEmpty {
                    httpClient.get<List<BlaseballSeasonStatsheet>>("https://www.blaseball.com/database/seasonStatsheets") {
                        parameter("ids", id)
                    }
                }
            }

            val teamStatsheetIDs = seasonStatsheets.flatMap(BlaseballSeasonStatsheet::teamStats)
            /*val teamStatsheets = if (teamStatsheetIDs.isNotEmpty()) {
                teamStatsheetIDs.chunked(8)
                    .flatMap { chunk ->
                        if (time == null) {
                            httpClient.get<List<BlaseballTeamStatsheet>>("https://www.blaseball.com/database/teamStatsheets") {
                                parameter("ids", chunk.joinParams())
                            }
                        } else {
                            httpClient.chroniclerEntityList<BlaseballTeamStatsheet>(chroniclerHost, "teamstatsheet", time) {
                                parameter("id", chunk.joinParams())
                            }
                        }
                    }.associateByTo(HashMap(), BlaseballTeamStatsheet::id)
            } else
                HashMap()

            teamStatsheetIDs.filterNot(teamStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    missing.chunked(8).forEach { chunk ->
                        httpClient.get<List<BlaseballTeamStatsheet>>("https://www.blaseball.com/database/teamStatsheets") {
                            parameter("ids", chunk.joinParams())
                        }.forEach { statsheet ->
                            teamStatsheets[statsheet.id] = statsheet
                        }
                    }
                }
            }

            val playerStatsheetIDs = teamStatsheets.values.flatMap(BlaseballTeamStatsheet::playerStats)
            val playerStatsheets = if (playerStatsheetIDs.isNotEmpty()) {
                playerStatsheetIDs.chunked(8).flatMap { chunk ->
                    if (time == null) {
                        httpClient.get<List<BlaseballPlayerStatsheet>>("https://www.blaseball.com/database/playerStatsheets") {
                            parameter("ids", chunk.joinParams())
                        }
                    } else {
                        httpClient.chroniclerEntityList<BlaseballPlayerStatsheet>(chroniclerHost, "playerstatsheet", time) {
                            parameter("id", chunk.joinParams())
                        }
                    }
                }.associateByTo(HashMap(), BlaseballPlayerStatsheet::id)
            } else
                HashMap()

            playerStatsheetIDs.filterNot(playerStatsheets::containsKey).let { missing ->
                if (missing.isNotEmpty()) {
                    missing.chunked(8).forEach { chunk ->
                        httpClient.get<List<BlaseballPlayerStatsheet>>("https://www.blaseball.com/database/playerStatsheets") {
                            parameter("ids", chunk.joinParams())
                        }.forEach { statsheet ->
                            playerStatsheets[statsheet.id] = statsheet
                        }
                    }
                }
            }*/

            val teamStatsheets = buildList<ConvenienceTeamStatsheet> {
                coroutineScope {
                    teamStatsheetIDs.map { teamStatsheetID ->
                        launch {
                            addAll(TEAM_STATSHEETS_BY_STAT_ID[Pair(time, teamStatsheetID.id)].await())
                        }
                    }.joinAll()
                }
            }

            return@buildCoroutines seasonStatsheets.map { seasonStatsheet ->
                ConvenienceSeasonStatsheet(
                    seasonStatsheet.id,
                    teamStatsheets,
                    seasonStatsheet.teamStats
                )
            }
        }

    val SEASON_BY_TIME = Caffeine.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .buildCoroutines<String, ConvenienceSeasonData> { time ->
            val time = if (time == "NOW") Clock.System.now().toString() else time
            val seasonDataDeferred = async { httpClient.chroniclerEntityMostRecent<BlaseballSeasonData>(chroniclerHost, "season", at = time) }
            val leagues = LEAGUE_TEAMS[time].asDeferred()

            val seasonData = seasonDataDeferred.await()

            val standings = async {
                httpClient.chroniclerEntityMostRecent<BlaseballStandings>(chroniclerHost, "standings", at = time) {
                    parameter("id", seasonData.standings.id)
                }
            }

            val stats = SEASON_STATSHEETS_BY_STAT_ID[Pair(time, seasonData.stats.id)].asDeferred()

            return@buildCoroutines ConvenienceSeasonData(
                seasonData.id,
                leagues.await().firstOrNull { league -> league.id == seasonData.league },
                seasonData.league,
                seasonData.rules,
                seasonData.schedule,
                seasonData.seasonNumber,
                standings.await(),
                seasonData.standings,
                stats.await().firstOrNull { stat -> stat.id == seasonData.stats },
                seasonData.stats,
                seasonData.terminology
            )
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
        .buildCoroutines<String, ConvenienceSeasonData> { season ->
            try {
                val seasonData = httpClient.get<BlaseballSeasonData>("https://www.blaseball.com/database/season") {
                    parameter("number", season)
                }

                val leagues = LEAGUE_TEAMS[SEASON_QUERY_BY_NUMBER[seasonData.seasonNumber].await()].asDeferred()

                val standings = async {
                    httpClient.chroniclerEntityMostRecent<BlaseballStandings>(chroniclerHost, "standings", at = Clock.System.now().toString()) {
                        parameter("id", seasonData.standings.id)
                    }
                }

                val stats = SEASON_STATSHEETS_BY_STAT_ID[Pair(null, seasonData.stats.id)].asDeferred()

                return@buildCoroutines ConvenienceSeasonData(
                    seasonData.id,
                    leagues.await().firstOrNull { league -> league.id == seasonData.league },
                    seasonData.league,
                    seasonData.rules,
                    seasonData.schedule,
                    seasonData.seasonNumber,
                    standings.await(),
                    seasonData.standings,
                    stats.await().firstOrNull { stat -> stat.id == seasonData.stats },
                    seasonData.stats,
                    seasonData.terminology
                )
            } catch (th: Throwable) {
                th.printStackTrace()
                throw th
            }
        }

    val SEASON_STATSHEETS_BY_SEASON = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildAsync<String, List<ConvenienceSeasonStatsheet>> { season, _ ->
            SEASON_BY_NUMBER[season].thenCompose { seasonData ->
                SEASON_STATSHEETS_BY_STAT_ID[null to seasonData.statsID.id]
            }
        }

    val SEASON_STATSHEETS_BY_TIME = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .buildAsync<String, List<ConvenienceSeasonStatsheet>> { time, _ ->
            SEASON_BY_TIME[time].thenCompose { seasonData ->
                SEASON_STATSHEETS_BY_STAT_ID[null to seasonData.statsID.id]
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

        val liveDataFlow = liveData.liveData

        jsonExplorer("/stream") { liveDataFlow.first().getJsonObject("value") }
    }
}