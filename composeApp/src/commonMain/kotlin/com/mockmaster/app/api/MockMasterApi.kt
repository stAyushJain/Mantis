package com.mockmaster.app.api

import com.mockmaster.app.platform.LocalStorage
import com.mockmaster.shared.ApiResult
import com.mockmaster.shared.CreateFolderReq
import com.mockmaster.shared.EnsureUserReq
import com.mockmaster.shared.EnsureUserResp
import com.mockmaster.shared.Flow
import com.mockmaster.shared.FolderNode
import com.mockmaster.shared.InterceptedCall
import com.mockmaster.shared.MockRule
import com.mockmaster.shared.RenameReq
import com.mockmaster.shared.ServerInfo
import com.mockmaster.shared.SetActiveUserReq
import com.mockmaster.shared.ShareFlowReq
import com.mockmaster.shared.ShareFlowResp
import com.mockmaster.shared.StartFlowReq
import com.mockmaster.shared.ToggleReq
import com.mockmaster.shared.UpsertFlowReq
import com.mockmaster.shared.UpsertRuleReq
import com.mockmaster.shared.UserExistsResp
import com.mockmaster.shared.UsersList
import com.mockmaster.shared.WorkspaceState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private const val LS_BASE_URL = "mockmaster.baseUrl"
private const val LS_USER = "mockmaster.user"

/**
 * Backend connection settings + identity. Both the URL and the username are
 * persisted to localStorage so the user doesn't have to retype them after
 * a refresh. The default URL is derived from where the page was loaded from
 * (see [defaultApiBaseUrl]) so the same image works on localhost and LAN.
 */
object ApiConfig {
    var baseUrl: String =
        LocalStorage.get(LS_BASE_URL)?.takeIf { it.isNotBlank() }
            ?: com.mockmaster.app.platform.defaultApiBaseUrl()
        set(value) {
            field = value
            LocalStorage.set(LS_BASE_URL, value)
        }

    /** Currently signed-in user, or null if the login screen should show. */
    var currentUser: String? = LocalStorage.get(LS_USER)?.takeIf { it.isNotBlank() }
        set(value) {
            field = value
            if (value == null) LocalStorage.remove(LS_USER)
            else LocalStorage.set(LS_USER, value)
        }
}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val client = HttpClient {
    install(ContentNegotiation) {
        json(json)
    }
    expectSuccess = false
}

private fun url(path: String) = ApiConfig.baseUrl.trimEnd('/') + path

/**
 * Thrown when the BE returns a non-2xx result for a CRUD call. Carries the
 * extracted `message` from the response body so dialogs can surface it inline.
 */
class ApiException(
    val statusCode: Int,
    val userMessage: String,
) : RuntimeException(userMessage)

private suspend fun HttpResponse.ensureOk(): HttpResponse {
    if (status.isSuccess()) return this
    val raw = runCatching { bodyAsText() }.getOrDefault("")
    val msg = parseErrorMessage(raw) ?: defaultMessageFor(status)
    throw ApiException(status.value, msg)
}

private fun parseErrorMessage(raw: String): String? {
    if (raw.isBlank()) return null
    return runCatching {
        val element = json.parseToJsonElement(raw)
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
        (obj["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }.getOrNull()
}

private fun defaultMessageFor(status: HttpStatusCode): String = when (status) {
    HttpStatusCode.Conflict -> "That name is already taken."
    HttpStatusCode.NotFound -> "Not found."
    HttpStatusCode.BadRequest -> "Invalid request."
    else -> "Request failed (${status.value})."
}

private fun io.ktor.client.request.HttpRequestBuilder.applyUserHeader() {
    ApiConfig.currentUser?.let { header("X-MockMaster-User", it) }
}

object MockMasterApi {
    // --- users (pseudo-auth) ---
    suspend fun listUsers(): UsersList = client.get(url("/users")).ensureOk().body()

    suspend fun ensureUser(username: String): EnsureUserResp =
        client.post(url("/users")) {
            contentType(ContentType.Application.Json)
            setBody(EnsureUserReq(username))
        }.ensureOk().body()

    suspend fun userExists(username: String): UserExistsResp =
        client.get(url("/users/$username")).ensureOk().body()

    suspend fun setActiveUser(username: String): ApiResult =
        client.post(url("/server/active-user")) {
            contentType(ContentType.Application.Json)
            setBody(SetActiveUserReq(username))
        }.ensureOk().body()

    // --- workspace / server ---
    suspend fun fetchWorkspace(): WorkspaceState =
        client.get(url("/workspace")) { applyUserHeader() }.ensureOk().body()

    suspend fun serverInfo(): ServerInfo =
        client.get(url("/server/info")).ensureOk().body()

    suspend fun connectProxy(): ApiResult =
        client.post(url("/server/connect")).ensureOk().body()

    suspend fun disconnectProxy(): ApiResult =
        client.post(url("/server/disconnect")).ensureOk().body()

    // --- folders ---
    suspend fun createFolder(name: String, parentId: String?): FolderNode =
        client.post(url("/folders")) {
            applyUserHeader()
            contentType(ContentType.Application.Json)
            setBody(CreateFolderReq(name, parentId))
        }.ensureOk().body()

    suspend fun renameFolder(id: String, name: String): ApiResult =
        client.put(url("/folders/$id")) {
            applyUserHeader()
            contentType(ContentType.Application.Json)
            setBody(RenameReq(name))
        }.ensureOk().body()

    suspend fun toggleFolder(id: String, enabled: Boolean): ApiResult =
        client.post(url("/folders/$id/toggle")) {
            applyUserHeader()
            contentType(ContentType.Application.Json)
            setBody(ToggleReq(enabled))
        }.ensureOk().body()

    suspend fun deleteFolder(id: String): ApiResult =
        client.delete(url("/folders/$id")) { applyUserHeader() }.ensureOk().body()

    // --- rules ---
    suspend fun listRules(folderId: String): List<MockRule> =
        client.get(url("/folders/$folderId/rules")) { applyUserHeader() }.ensureOk().body()

    suspend fun upsertRule(folderId: String, req: UpsertRuleReq): MockRule {
        val resp = if (req.id != null) {
            client.put(url("/folders/$folderId/rules/${req.id}")) {
                applyUserHeader()
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        } else {
            client.post(url("/folders/$folderId/rules")) {
                applyUserHeader()
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }
        return resp.ensureOk().body()
    }

    suspend fun deleteRule(folderId: String, ruleId: String): ApiResult =
        client.delete(url("/folders/$folderId/rules/$ruleId")) { applyUserHeader() }
            .ensureOk().body()

    suspend fun toggleRule(folderId: String, ruleId: String, enabled: Boolean): ApiResult =
        client.post(url("/folders/$folderId/rules/$ruleId/toggle")) {
            applyUserHeader()
            contentType(ContentType.Application.Json)
            setBody(ToggleReq(enabled))
        }.ensureOk().body()

    // --- flows ---
    suspend fun listFlows(): List<Flow> =
        client.get(url("/flows")) { applyUserHeader() }.ensureOk().body()

    suspend fun upsertFlow(req: UpsertFlowReq): Flow {
        val resp = if (req.id != null) {
            client.put(url("/flows/${req.id}")) {
                applyUserHeader()
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        } else {
            client.post(url("/flows")) {
                applyUserHeader()
                contentType(ContentType.Application.Json)
                setBody(req)
            }
        }
        return resp.ensureOk().body()
    }

    suspend fun deleteFlow(id: String): ApiResult =
        client.delete(url("/flows/$id")) { applyUserHeader() }.ensureOk().body()

    suspend fun shareFlow(id: String, targetUser: String): ShareFlowResp =
        client.post(url("/flows/$id/share")) {
            applyUserHeader()
            contentType(ContentType.Application.Json)
            setBody(ShareFlowReq(targetUser))
        }.ensureOk().body()

    suspend fun startFlow(id: String): ApiResult =
        client.post(url("/flows/start")) {
            applyUserHeader()
            contentType(ContentType.Application.Json)
            setBody(StartFlowReq(id))
        }.ensureOk().body()

    suspend fun stopFlow(): ApiResult =
        client.post(url("/flows/stop")) { applyUserHeader() }.ensureOk().body()

    // --- logs ---
    suspend fun listLogs(): List<InterceptedCall> =
        client.get(url("/logs")).ensureOk().body()

    suspend fun clearLogs(): ApiResult =
        client.delete(url("/logs/clear")).ensureOk().body()

    fun streamLogsUrl(): String = url("/logs/stream")

    fun certUrl(): String = url("/cert")
}
