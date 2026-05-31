package com.mockmaster.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FolderNode(
    val id: String,
    val name: String,
    @SerialName("isEnabled") val isEnabled: Boolean = true,
    @SerialName("subFolders") val subFolders: List<FolderNode> = emptyList(),
)

@Serializable
data class MockRule(
    val id: String,
    val path: String,
    val method: String = "GET",
    @SerialName("statusCode") val statusCode: Int,
    val body: String,
    @SerialName("isEnabled") val isEnabled: Boolean = true,
    @SerialName("createdAt") val createdAt: Long = 0L,
)

@Serializable
data class FlowStep(
    val id: String,
    val path: String,
    val method: String = "GET",
    @SerialName("statusCode") val statusCode: Int,
    val body: String,
    @SerialName("isConsumed") val isConsumed: Boolean = false,
)

@Serializable
data class Flow(
    val id: String,
    val name: String,
    val description: String = "",
    val steps: List<FlowStep> = emptyList(),
    @SerialName("createdAt") val createdAt: Long = 0L,
)

@Serializable
data class WorkspaceState(
    val folders: List<FolderNode> = emptyList(),
    @SerialName("rulesMap") val rulesMap: Map<String, List<MockRule>> = emptyMap(),
    val flows: List<Flow> = emptyList(),
    @SerialName("activeFlowId") val activeFlowId: String? = null,
)

// ----- API request payloads (kept here so kotlinx.serialization is centralised) -----

@Serializable
data class CreateFolderReq(val name: String, @SerialName("parentId") val parentId: String? = null)

@Serializable
data class RenameReq(val name: String)

@Serializable
data class ToggleReq(val enabled: Boolean)

@Serializable
data class UpsertRuleReq(
    val id: String? = null,
    val path: String,
    val method: String = "GET",
    @SerialName("statusCode") val statusCode: Int,
    val body: String,
    @SerialName("isEnabled") val isEnabled: Boolean = true,
)

@Serializable
data class UpsertFlowStepReq(
    val id: String? = null,
    val path: String,
    val method: String = "GET",
    @SerialName("statusCode") val statusCode: Int,
    val body: String,
)

@Serializable
data class UpsertFlowReq(
    val id: String? = null,
    val name: String,
    val description: String = "",
    val steps: List<UpsertFlowStepReq>,
)

@Serializable
data class StartFlowReq(@SerialName("flowId") val flowId: String)

@Serializable
data class InterceptedCall(
    val id: String,
    val method: String,
    val url: String,
    val path: String,
    @SerialName("statusCode") val statusCode: Int,
    val matched: Boolean,
    @SerialName("matchSource") val matchSource: String,
    @SerialName("matchLabel") val matchLabel: String,
    @SerialName("requestHeaders") val requestHeaders: Map<String, String> = emptyMap(),
    @SerialName("requestBody") val requestBody: String = "",
    @SerialName("responseBody") val responseBody: String = "",
    val timestamp: String,
)

@Serializable
data class ServerInfo(
    val status: String,
    @SerialName("proxyHost") val proxyHost: String,
    @SerialName("proxyPort") val proxyPort: Int,
    @SerialName("apiPort") val apiPort: Int,
    @SerialName("localIp") val localIp: String? = null,
    val version: String,
)

@Serializable
data class ApiResult(
    val ok: Boolean,
    val message: String? = null,
)

// ----- User (pseudo-auth) -----

@Serializable
data class UsersList(
    val users: List<String> = emptyList(),
    val active: String = "default",
)

@Serializable
data class EnsureUserReq(val username: String)

@Serializable
data class EnsureUserResp(val username: String, val created: Boolean)

@Serializable
data class UserExistsResp(val exists: Boolean, val username: String)

@Serializable
data class SetActiveUserReq(val username: String)

@Serializable
data class ShareFlowReq(@SerialName("targetUser") val targetUser: String)

@Serializable
data class ShareFlowResp(
    val ok: Boolean,
    @SerialName("newFlowId") val newFlowId: String,
    @SerialName("targetUser") val targetUser: String,
    val message: String? = null,
)
