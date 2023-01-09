package dev.brella.corsmechanics.baal

import io.ktor.http.*
import io.ktor.util.*
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Option
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import java.time.Duration

@Suppress("SqlResolve")
class Altar(config: JsonObject) {
    val connectionFactory: ConnectionFactory = ConnectionFactories.get(
        config.run {
            val builder = getStringOrNull("url")?.let { ConnectionFactoryOptions.parse(it).mutate() }
                ?: ConnectionFactoryOptions.builder().option(ConnectionFactoryOptions.DRIVER, "pool")
                    .option(ConnectionFactoryOptions.PROTOCOL, "postgresql")

            getStringOrNull("connectTimeout")
                ?.let { builder.option(ConnectionFactoryOptions.CONNECT_TIMEOUT, Duration.parse(it)) }

            getStringOrNull("database")
                ?.let { builder.option(ConnectionFactoryOptions.DATABASE, it) }

            getStringOrNull("driver")
                ?.let { builder.option(ConnectionFactoryOptions.DRIVER, it) }

            getStringOrNull("host")
                ?.let { builder.option(ConnectionFactoryOptions.HOST, it) }

            getStringOrNull("password")
                ?.let { builder.option(ConnectionFactoryOptions.PASSWORD, it) }

            getStringOrNull("port")?.toIntOrNull()
                ?.let { builder.option(ConnectionFactoryOptions.PORT, it) }

            getStringOrNull("protocol")
                ?.let { builder.option(ConnectionFactoryOptions.PROTOCOL, it) }

            getStringOrNull("ssl")?.toBoolean()
                ?.let { builder.option(ConnectionFactoryOptions.SSL, it) }

            getStringOrNull("user")
                ?.let { builder.option(ConnectionFactoryOptions.USER, it) }

            getJsonObjectOrNull("options")?.forEach { (key, value) ->
                val value = value as? JsonPrimitive ?: return@forEach
                value.longOrNull?.let { builder.option(Option.valueOf(key), it) }
                    ?: value.doubleOrNull?.let { builder.option(Option.valueOf(key), it) }
                    ?: value.booleanOrNull?.let { builder.option(Option.valueOf(key), it) }
                    ?: value.contentOrNull?.let { builder.option(Option.valueOf(key), it) }
            }

            builder.build()
        }
    )

    val client = DatabaseClient.create(connectionFactory)
    val initJob = Baal.launch {
        client.sql(
            """CREATE TABLE IF NOT EXISTS access_log (
            |    id BIGSERIAL PRIMARY KEY,
            |    request_host VARCHAR(128) NOT NULL,
            |    request_path VARCHAR(256) NOT NULL,
            |    request_method VARCHAR(32) NOT NULL,
            |    content_type VARCHAR(256) NOT NULL,
            |    response_version VARCHAR(16) NOT NULL,
            |    response_code int NOT NULL,
            |    headers jsonb NOT NULL,
            |    body bytea NOT NULL,
            |    request_time BIGINT NOT NULL
            |);""".trimMargin()
        ).await()

        client.sql(
            """CREATE TABLE IF NOT EXISTS websocket_messages (
            |   id BIGSERIAL PRIMARY KEY,
            |   client BIGINT NOT NULL,
            |   msg bytea NOT NULL,
            |   incoming BOOL NOT NULL
            |);""".trimMargin()
        ).await()
    }

    suspend fun accessLog(
        host: String,
        path: String,
        method: String,
        contentType: String,
        responseVersion: String,
        responseCode: Int,
        headers: StringValues,
        body: ByteArray,
        requestTime: Long,
    ): Long =
        client.sql("INSERT INTO access_log (request_host, request_path, request_method, content_type, response_version, response_code, headers, body, request_time) VALUES (\$1, \$2, \$3, \$4, \$5, \$6, \$7, \$8, \$9) RETURNING id")
            .bind("\$1", host)
            .bind("\$2", path)
            .bind("\$3", method)
            .bind("\$4", contentType)
            .bind("\$5", responseVersion)
            .bind("\$6", responseCode)
            .bind("\$7", io.r2dbc.postgresql.codec.Json.of(buildJsonObject {
                headers.forEach { name, values ->
                    putJsonArray(name) { values.forEach(this::add) }
                }
            }.toString()))
            .bind("\$8", body)
            .bind("\$9", requestTime)
            .map { row -> row.get("id", java.lang.Long::class.java) }
            .awaitSingle()
            .toLong()

    suspend fun websocketMessage(client: Long, msg: ByteArray, incoming: Boolean) =
        this.client.sql("INSERT INTO websocket_messages (client, msg, incoming) VALUES ($1, $2, $3)")
            .bind("\$1", client)
            .bind("\$2", msg)
            .bind("\$3", incoming)
            .await()
}