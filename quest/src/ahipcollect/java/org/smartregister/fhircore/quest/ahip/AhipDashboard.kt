/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ahip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Today
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import org.smartregister.fhircore.quest.R

/** A data collector's summary of the AHIP assessments they submitted on this device. */
data class AhipDashboard(
  val total: Int,
  val today: Int,
  val thisWeek: Int,
  val pendingSync: Int,
  val perTool: List<AhipToolCount>,
  val recent: List<AhipRecentSubmission>,
) {
  companion object {
    val EMPTY = AhipDashboard(0, 0, 0, 0, AhipCatalog.tools.map { AhipToolCount(it, 0, null) }, emptyList())
  }
}

data class AhipToolCount(val tool: AhipTool, val count: Int, val lastSubmitted: Date?)

data class AhipRecentSubmission(
  val tool: AhipTool,
  val site: String?,
  val assessment: String?,
  val authored: Date?,
  val synced: Boolean,
)

@Composable
fun AhipDashboardScreen(
  dashboard: AhipDashboard?,
  username: String?,
  syncStatus: AhipSyncStatus,
  onBack: () -> Unit,
  onSync: () -> Unit,
  aiMessages: List<AhipAiMessage> = emptyList(),
  aiBusy: Boolean = false,
  onAskAi: (String) -> Unit = {},
  onClearAi: () -> Unit = {},
) {
  var showAi by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  if (showAi) {
    AhipAiChatDialog(aiMessages, aiBusy, onAskAi, onClearAi, onDismiss = { showAi = false })
  }
  AhipScaffold(
    title = stringResource(R.string.ahip_dashboard_title),
    onBack = onBack,
    bottomBar = {
      Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
        when (syncStatus) {
          AhipSyncStatus.SUCCEEDED -> StatusText(true, stringResource(R.string.ahip_sync_succeeded))
          AhipSyncStatus.FAILED -> StatusText(false, stringResource(R.string.ahip_sync_failed))
          AhipSyncStatus.NO_SERVER -> StatusText(false, stringResource(R.string.ahip_server_missing))
          else -> Unit
        }
        if (syncStatus in setOf(AhipSyncStatus.SUCCEEDED, AhipSyncStatus.FAILED, AhipSyncStatus.NO_SERVER)) {
          Spacer(Modifier.height(8.dp))
        }
        AhipPrimaryButton(
          text =
            stringResource(
              if (syncStatus == AhipSyncStatus.RUNNING) R.string.ahip_syncing
              else R.string.ahip_sync_to_database,
            ),
          onClick = onSync,
          color = AhipColors.Purple,
          enabled = syncStatus != AhipSyncStatus.RUNNING,
          leadingIcon = Icons.Filled.CloudUpload,
        )
      }
    },
  ) { modifier ->
    if (dashboard == null) {
      Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AhipColors.Navy)
      }
      return@AhipScaffold
    }
    LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        if (!username.isNullOrBlank()) {
          Text(
            stringResource(R.string.ahip_dashboard_collector, username),
            color = AhipColors.TextMuted,
            fontSize = 14.sp,
          )
        }
      }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          StatTile(Icons.Outlined.Assignment, dashboard.total, R.string.ahip_dashboard_total, AhipColors.Navy, Modifier.weight(1f))
          StatTile(Icons.Outlined.Today, dashboard.today, R.string.ahip_dashboard_today, AhipColors.Green, Modifier.weight(1f))
        }
      }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          StatTile(Icons.Outlined.DateRange, dashboard.thisWeek, R.string.ahip_dashboard_week, AhipColors.Purple, Modifier.weight(1f))
          StatTile(
            Icons.Outlined.CloudQueue,
            dashboard.pendingSync,
            R.string.ahip_dashboard_pending,
            if (dashboard.pendingSync > 0) AhipColors.Purple else AhipColors.Green,
            Modifier.weight(1f),
          )
        }
      }
      item {
        AiCard(
          onOpen = { showAi = true },
          onQuick = { question ->
            showAi = true
            onAskAi(question)
          },
        )
      }
      item { SectionCard(stringResource(R.string.ahip_dashboard_by_tool)) { ToolBars(dashboard.perTool) } }
      item {
        SectionCard(stringResource(R.string.ahip_dashboard_recent)) {
          if (dashboard.recent.isEmpty()) {
            Text(stringResource(R.string.ahip_dashboard_empty), color = AhipColors.TextMuted)
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
              dashboard.recent.forEach { RecentRow(it) }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StatTile(icon: ImageVector, value: Int, label: Int, color: Color, modifier: Modifier) {
  Row(
    modifier =
      modifier
        .clip(CardShape)
        .background(Color.White)
        .border(BorderStroke(1.dp, AhipColors.Border), CardShape)
        .padding(14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(color),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
    Spacer(Modifier.width(12.dp))
    Column {
      Text("$value", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
      Text(stringResource(label), fontSize = 13.sp, color = AhipColors.TextMuted, maxLines = 2)
    }
  }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
  Column(
    Modifier.fillMaxWidth()
      .clip(CardShape)
      .background(Color.White)
      .border(BorderStroke(1.dp, AhipColors.Border), CardShape),
  ) {
    Text(
      title,
      color = Color.White,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.fillMaxWidth().background(AhipColors.Navy).padding(horizontal = 14.dp, vertical = 10.dp),
    )
    Box(Modifier.padding(14.dp)) { content() }
  }
}

@Composable
private fun ToolBars(perTool: List<AhipToolCount>) {
  val max = perTool.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    perTool.forEach { row ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        ToolIconTile(row.tool, size = 32)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text(
            stringResource(R.string.ahip_tool_title, row.tool.number, row.tool.title),
            color = AhipColors.Navy,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.height(4.dp))
          Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(AhipColors.Background)) {
            if (row.count > 0) {
              Box(
                Modifier.fillMaxWidth(row.count / max.toFloat())
                  .height(8.dp)
                  .clip(RoundedCornerShape(4.dp))
                  .background(if (row.tool.number % 2 == 1) AhipColors.Navy else AhipColors.Green),
              )
            }
          }
        }
        Spacer(Modifier.width(10.dp))
        Text("${row.count}", fontWeight = FontWeight.Bold, color = AhipColors.Navy, fontSize = 16.sp)
      }
    }
  }
}

@Composable
private fun RecentRow(item: AhipRecentSubmission) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    ToolIconTile(item.tool, size = 32)
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(
        item.site ?: stringResource(R.string.ahip_tool_title, item.tool.number, item.tool.title),
        color = AhipColors.Navy,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        listOfNotNull(
            "Tool ${item.tool.number}",
            item.assessment,
            item.authored?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it) },
          )
          .joinToString(" · "),
        color = AhipColors.TextMuted,
        fontSize = 12.sp,
        maxLines = 2,
      )
    }
    Spacer(Modifier.width(8.dp))
    Icon(
      if (item.synced) Icons.Filled.CloudDone else Icons.Outlined.CloudQueue,
      contentDescription =
        stringResource(if (item.synced) R.string.ahip_dashboard_synced else R.string.ahip_dashboard_not_synced),
      tint = if (item.synced) AhipColors.Green else AhipColors.Purple,
    )
  }
}

// AI assistant for the collector's own data (answers from AHIP Manage; needs the office Wi-Fi).

private val AI_QUICK_QUESTIONS =
  listOf(
    R.string.ahip_ai_q_summary,
    R.string.ahip_ai_q_fix,
    R.string.ahip_ai_q_week,
    R.string.ahip_ai_q_next,
  )

@Composable
private fun AiCard(onOpen: () -> Unit, onQuick: (String) -> Unit) {
  Column(
    Modifier.fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(AhipColors.Purple, AhipColors.Navy)))
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(stringResource(R.string.ahip_ai_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text(stringResource(R.string.ahip_ai_intro), color = Color(0xFFDDE3F7), fontSize = 13.sp)
    AI_QUICK_QUESTIONS.chunked(2).forEach { pair ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pair.forEach { res ->
          val question = stringResource(res)
          Text(
            question,
            color = Color.White,
            fontSize = 13.sp,
            modifier =
              Modifier.weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .clickable { onQuick(question) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
          )
        }
      }
    }
    Text(
      stringResource(R.string.ahip_ai_open),
      color = AhipColors.Navy,
      fontWeight = FontWeight.Bold,
      modifier =
        Modifier.clip(RoundedCornerShape(20.dp))
          .background(Color.White)
          .clickable(onClick = onOpen)
          .padding(horizontal = 16.dp, vertical = 8.dp),
    )
  }
}

@Composable
private fun AhipAiChatDialog(
  messages: List<AhipAiMessage>,
  busy: Boolean,
  onAsk: (String) -> Unit,
  onClear: () -> Unit,
  onDismiss: () -> Unit,
) {
  var input by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
  val listState = androidx.compose.foundation.lazy.rememberLazyListState()
  androidx.compose.runtime.LaunchedEffect(messages.size, busy) {
    val count = messages.size + (if (busy) 1 else 0) + (if (messages.isEmpty()) AI_QUICK_QUESTIONS.size + 1 else 0)
    if (count > 0) listState.animateScrollToItem(count - 1)
  }
  androidx.compose.ui.window.Dialog(
    onDismissRequest = onDismiss,
    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Column(
      Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(18.dp)).background(AhipColors.Background),
    ) {
      Row(
        Modifier.fillMaxWidth().background(AhipColors.Navy).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(stringResource(R.string.ahip_ai_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
          Text(stringResource(R.string.ahip_ai_subtitle), color = Color(0xFFC9D0EC), fontSize = 12.sp)
        }
        Text(
          stringResource(R.string.ahip_ai_new),
          color = Color.White,
          fontSize = 13.sp,
          modifier = Modifier.clickable(onClick = onClear).padding(8.dp),
        )
        Text("✕", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
      }
      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (messages.isEmpty()) {
          item { Text(stringResource(R.string.ahip_ai_empty), color = AhipColors.TextMuted, fontSize = 14.sp) }
          items(AI_QUICK_QUESTIONS) { res ->
            val question = stringResource(res)
            Text(
              question,
              color = AhipColors.Navy,
              fontSize = 14.sp,
              modifier =
                Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .background(Color.White)
                  .clickable { onAsk(question) }
                  .padding(12.dp),
            )
          }
        }
        items(messages) { m ->
          Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
            Text(
              m.text,
              color = if (m.fromUser) Color.White else if (m.error) Color(0xFF8A1C1C) else AhipColors.Navy,
              fontSize = 14.sp,
              modifier =
                Modifier.fillMaxWidth(0.88f)
                  .clip(RoundedCornerShape(14.dp))
                  .background(if (m.fromUser) AhipColors.Navy else if (m.error) Color(0xFFFDECEC) else Color.White)
                  .padding(12.dp),
            )
          }
        }
        if (busy) {
          item {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(color = AhipColors.Purple, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
              Text(stringResource(R.string.ahip_ai_thinking), color = AhipColors.TextMuted, fontSize = 13.sp)
            }
          }
        }
      }
      Row(
        Modifier.fillMaxWidth().background(Color.White).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        androidx.compose.material.OutlinedTextField(
          value = input,
          onValueChange = { input = it },
          placeholder = { Text(stringResource(R.string.ahip_ai_hint)) },
          modifier = Modifier.weight(1f),
          maxLines = 3,
        )
        Spacer(Modifier.width(8.dp))
        Text(
          stringResource(R.string.ahip_ai_send),
          color = Color.White,
          fontWeight = FontWeight.Bold,
          modifier =
            Modifier.clip(RoundedCornerShape(20.dp))
              .background(if (busy || input.isBlank()) AhipColors.TextMuted else AhipColors.Purple)
              .clickable(enabled = !busy && input.isNotBlank()) {
                onAsk(input)
                input = ""
              }
              .padding(horizontal = 16.dp, vertical = 10.dp),
        )
      }
      Text(
        stringResource(R.string.ahip_ai_privacy),
        color = AhipColors.TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().background(Color.White).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
      )
    }
  }
}
