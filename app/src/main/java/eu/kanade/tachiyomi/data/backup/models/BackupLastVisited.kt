package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupLastVisited(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var lastVisitedPage: Long,
    @ProtoNumber(3) var visitedAt: Long,
)
