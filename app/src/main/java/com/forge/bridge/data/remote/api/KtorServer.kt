package com.forge.bridge.data.remote.api

import com.forge.bridge.data.local.KeystoreVault
import com.forge.bridge.data.local.dao.AuditLogDao
import com.forge.bridge.data.local.dao.ProviderDao
import com.forge.bridge.data.local.entities.AuditLogEntity
import com.forge.bridge.data.local.entities.ProviderEntity
import com.forge.bridge.data.model.GenerationRequest
import com.forge.bridge.data.repository.FileRepository
import com.forge.bridge.data.repository.GenerationRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.sse.SSE
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KtorServer @Inject constructor(
    private val json: Json,
    private val providerDao: ProviderDao,
    private val auditLogDao: AuditLogDao,
    private val generationRepository: GenerationRepository,
    private val fileRepository: FileRepository,
    private val keystoreVault: KeystoreVault
) {
    private var server = embeddedServer(Netty, port = 8745, host = "127.0.0.1") {
        install(ContentNegotiation) {
            json(json)
        }
        install(SSE)
        install(CORS) {
            allowHost("localhost")
            allowHost("127.0.0.1")
        }
        routing {
            get("/") {
                call.respondText("Forge Bridge is running!")
            }

            get("/api/v1/status") {
                call.respondText("{ \"status\": \"online\" }", contentType = ContentType.Application.Json)
            }

            get("/api/v1/providers") {
                val providers = providerDao.getProvidersList()
                val safeProviders = providers.map { it.copy(encryptedToken = "***") }
                call.respond(safeProviders)
            }

            post("/api/v1/providers") {
                val body = call.receive<Map<String, String>>()
                val id = body["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val token = body["token"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val type = body["type"] ?: "openai"
                val name = body["name"] ?: id

                val encrypted = keystoreVault.encrypt(token)
                providerDao.upsertProvider(ProviderEntity(id, name, type, encrypted))
                call.respond(HttpStatusCode.Created)
            }

            delete("/api/v1/providers/{id}") {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                providerDao.deleteProvider(id)
                call.respond(HttpStatusCode.OK)
            }

            post("/api/v1/files/upload") {
                // Simplified multi-part handling
                val multipart = call.receiveMultipart()
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val name = part.originalFileName ?: "unknown"
                        val bytes = part.streamProvider().readBytes()
                        fileRepository.saveFile(name, bytes)
                    }
                    part.dispose()
                }
                call.respond(HttpStatusCode.Created, "{ \"status\": \"uploaded\" }")
            }

            sse("/api/v1/generate") {
                val request = call.receive<GenerationRequest>()
                var success = true
                generationRepository.generate(request)
                    .onEach { chunk ->
                        send(ServerSentEvent(json.encodeToString(chunk)))
                    }
                    .onCompletion { error ->
                        val status = if (error == null) "success" else "error"
                        auditLogDao.insertLog(
                            AuditLogEntity(
                                provider = request.provider,
                                model = request.model,
                                status = status,
                                messageCount = request.messages.size
                            )
                        )
                    }
                    .collect()
            }
        }
    }

    fun start() {
        server.start(wait = false)
    }

    fun stop() {
        server.stop(1000, 2000)
    }
}
