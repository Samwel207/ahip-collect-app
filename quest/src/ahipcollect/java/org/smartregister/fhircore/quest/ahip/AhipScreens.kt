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

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedButton
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.quest.R

internal val CardShape = RoundedCornerShape(12.dp)

// Screen 1: Get Started

@Composable
fun AhipWelcomeScreen(
  status: AhipConnectStatus,
  serverHost: String,
  onGetStarted: () -> Unit,
  onContinueOffline: () -> Unit,
) {
  Box(Modifier.fillMaxSize().background(Color.White)) {
    AhipSwoosh(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp))
    AhipWelcomeContent(status, serverHost, onGetStarted, onContinueOffline)
  }
}

/** Green and purple waves along the bottom edge, as on the AHIP Collect launch mockup. */
@Composable
private fun AhipSwoosh(modifier: Modifier) {
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val green =
      Path().apply {
        moveTo(0f, h * 0.55f)
        cubicTo(w * 0.35f, h * 0.15f, w * 0.65f, h * 0.75f, w, h * 0.30f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
      }
    val purple =
      Path().apply {
        moveTo(0f, h * 0.85f)
        cubicTo(w * 0.40f, h * 0.45f, w * 0.70f, h * 1.0f, w, h * 0.55f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
      }
    drawPath(green, AhipColors.Green)
    drawPath(purple, AhipColors.Purple)
  }
}

@Composable
private fun AhipWelcomeContent(
  status: AhipConnectStatus,
  serverHost: String,
  onGetStarted: () -> Unit,
  onContinueOffline: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.weight(1f))
    Image(
      painter = painterResource(R.drawable.ahip_logo),
      contentDescription = null,
      modifier = Modifier.fillMaxWidth(0.8f),
    )
    Spacer(Modifier.height(16.dp))
    Text(
      text = stringResource(R.string.ahip_collect),
      fontSize = 30.sp,
      fontWeight = FontWeight.Bold,
      color = AhipColors.Navy,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      text = stringResource(R.string.ahip_tagline),
      fontSize = 16.sp,
      color = AhipColors.Navy,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.weight(1.4f))
    when (status) {
      AhipConnectStatus.IDLE ->
        AhipPrimaryButton(
          text = stringResource(R.string.ahip_get_started),
          onClick = onGetStarted,
          color = AhipColors.Purple,
        )
      AhipConnectStatus.SEARCHING ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(52.dp)) {
          CircularProgressIndicator(color = AhipColors.Purple, modifier = Modifier.size(24.dp))
          Spacer(Modifier.width(12.dp))
          Text(stringResource(R.string.ahip_searching_server), color = AhipColors.Navy)
        }
      AhipConnectStatus.NOT_FOUND,
      AhipConnectStatus.NO_WIFI, -> {
        Text(
          text =
            if (status == AhipConnectStatus.NO_WIFI) {
              stringResource(R.string.ahip_no_wifi)
            } else {
              stringResource(R.string.ahip_server_not_found, serverHost)
            },
          color = AhipColors.Navy,
          textAlign = TextAlign.Center,
          fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        AhipPrimaryButton(
          text = stringResource(R.string.ahip_try_again),
          onClick = onGetStarted,
          color = AhipColors.Purple,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
          onClick = onContinueOffline,
          modifier = Modifier.fillMaxWidth().height(52.dp),
          shape = RoundedCornerShape(26.dp),
          border = BorderStroke(1.dp, AhipColors.Navy),
        ) {
          Text(
            stringResource(R.string.ahip_continue_offline),
            color = AhipColors.Navy,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    }
  }
}

// Shared chrome

@Composable
fun AhipScaffold(
  title: String,
  onBack: (() -> Unit)?,
  menu: (@Composable () -> Unit)? = null,
  bottomBar: @Composable () -> Unit = {},
  content: @Composable (Modifier) -> Unit,
) {
  Scaffold(
    backgroundColor = AhipColors.Background,
    topBar = {
      TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1) },
        backgroundColor = AhipColors.Navy,
        contentColor = Color.White,
        navigationIcon =
          onBack?.let {
            {
              IconButton(onClick = it) {
                Icon(
                  Icons.AutoMirrored.Filled.ArrowBack,
                  contentDescription = stringResource(R.string.ahip_back),
                )
              }
            }
          },
        actions = { menu?.invoke() },
      )
    },
    bottomBar = bottomBar,
  ) { padding ->
    content(Modifier.padding(padding))
  }
}

@Composable
fun AhipOverflowMenu(
  username: String?,
  serverHost: String,
  onSync: () -> Unit,
  onLogout: () -> Unit,
  onShareData: () -> Unit = {},
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) {
      Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.ahip_menu))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      if (!username.isNullOrBlank()) {
        Text(
          text = stringResource(R.string.ahip_signed_in_as, username),
          color = AhipColors.TextMuted,
          fontSize = 13.sp,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
      Text(
        text = stringResource(R.string.ahip_server_label, serverHost),
        color = AhipColors.TextMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
      )
      DropdownMenuItem(
        onClick = {
          expanded = false
          onSync()
        },
      ) {
        Text(stringResource(R.string.ahip_sync_data))
      }
      DropdownMenuItem(
        onClick = {
          expanded = false
          onShareData()
        },
      ) {
        Text(stringResource(R.string.ahip_share_data))
      }
      DropdownMenuItem(
        onClick = {
          expanded = false
          onLogout()
        },
      ) {
        Text(stringResource(R.string.ahip_log_out))
      }
    }
  }
}

@Composable
fun AhipPrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier.fillMaxWidth(),
  color: Color = AhipColors.Green,
  enabled: Boolean = true,
  leadingIcon: ImageVector? = null,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.height(52.dp),
    shape = RoundedCornerShape(26.dp),
    colors = ButtonDefaults.buttonColors(backgroundColor = color, contentColor = Color.White),
  ) {
    leadingIcon?.let {
      Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(8.dp))
    }
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    if (leadingIcon == null) {
      Spacer(Modifier.width(8.dp))
      Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Composable
private fun AhipBottomBar(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) { content() }
}

@Composable
private fun SelectableCard(
  selected: Boolean,
  enabled: Boolean = true,
  onClick: () -> Unit,
  content: @Composable () -> Unit,
) {
  val borderColor = if (selected) AhipColors.Green else AhipColors.Border
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(CardShape)
        .background(if (selected) AhipColors.GreenTint else Color.White)
        .border(BorderStroke(if (selected) 2.dp else 1.dp, borderColor), CardShape)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    content()
  }
}

/** Returns a function that tells the user the named study / assessment is not available yet. */
@Composable
private fun rememberComingSoonMessage(): (String) -> Unit {
  val context = LocalContext.current
  return remember(context) {
    { name ->
      Toast.makeText(
          context,
          context.getString(R.string.ahip_coming_soon_message, name),
          Toast.LENGTH_SHORT,
        )
        .show()
    }
  }
}

@Composable
private fun ComingSoonChip() {
  Text(
    stringResource(R.string.ahip_coming_soon),
    color = AhipColors.Purple,
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    modifier =
      Modifier.clip(RoundedCornerShape(10.dp))
        .background(AhipColors.PurpleTint)
        .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

@Composable
private fun ScreenHeading(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    color = AhipColors.Navy,
    modifier = modifier,
  )
}

// Screen 3: Select study

@Composable
fun AhipStudyScreen(
  selectedStudyId: String,
  onSelectStudy: (String) -> Unit,
  onContinue: () -> Unit,
  menu: @Composable () -> Unit,
) {
  AhipScaffold(
    title = stringResource(R.string.ahip_collect),
    onBack = null,
    menu = menu,
    bottomBar = {
      AhipBottomBar {
        AhipPrimaryButton(text = stringResource(R.string.ahip_continue), onClick = onContinue)
      }
    },
  ) { modifier ->
    Column(modifier.fillMaxSize()) {
      Text(
        text = stringResource(R.string.ahip_select_study),
        color = Color.White,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier =
          Modifier.fillMaxWidth()
            .background(AhipColors.Navy)
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 8.dp),
      )
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val showComingSoon = rememberComingSoonMessage()
        AhipCatalog.studies.forEach { study ->
          SelectableCard(
            selected = study.id == selectedStudyId,
            onClick = {
              if (study.enabled) onSelectStudy(study.id) else showComingSoon(study.title)
            },
          ) {
            Icon(
              Icons.Outlined.Folder,
              contentDescription = null,
              tint = if (study.enabled) AhipColors.Navy else AhipColors.TextMuted,
              modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
              Text(
                study.title,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = if (study.enabled) AhipColors.Navy else AhipColors.TextMuted,
              )
              Text("(${study.subtitle})", color = AhipColors.TextMuted, fontSize = 14.sp)
            }
            if (!study.enabled) ComingSoonChip()
            if (study.id == selectedStudyId) {
              Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AhipColors.Green)
            }
          }
        }
      }
    }
  }
}

// Screen 4: Select assessment

@Composable
fun AhipAssessmentScreen(
  studyTitle: String,
  selectedAssessmentId: String,
  onSelectAssessment: (String) -> Unit,
  onBack: () -> Unit,
  onContinue: () -> Unit,
) {
  AhipScaffold(
    title = stringResource(R.string.ahip_study_label, studyTitle),
    onBack = onBack,
    bottomBar = {
      AhipBottomBar {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedButton(
            onClick = onBack,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.dp, AhipColors.Navy),
          ) {
            Text(stringResource(R.string.ahip_back), color = AhipColors.Navy)
          }
          AhipPrimaryButton(
            text = stringResource(R.string.ahip_continue),
            onClick = onContinue,
            modifier = Modifier.weight(1f),
          )
        }
      }
    },
  ) { modifier ->
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ScreenHeading(stringResource(R.string.ahip_select_assessment))
      val showComingSoon = rememberComingSoonMessage()
      AhipCatalog.assessments.forEach { assessment ->
        val selected = assessment.id == selectedAssessmentId
        val onClick = {
          if (assessment.available) {
            onSelectAssessment(assessment.id)
          } else {
            showComingSoon(assessment.title)
          }
        }
        SelectableCard(selected = selected, onClick = onClick) {
          RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = AhipColors.Green),
          )
          Spacer(Modifier.width(8.dp))
          Text(
            "${assessment.title} (${assessment.period})",
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (assessment.available) AhipColors.Navy else AhipColors.TextMuted,
            modifier = Modifier.weight(1f),
          )
          if (!assessment.available) ComingSoonChip()
        }
      }
    }
  }
}

// Screen 5: Tools list

@Composable
fun AhipToolsScreen(
  assessment: AhipAssessment,
  toolDetails: Map<String, AhipToolDetails>,
  drafts: List<AhipDraft>,
  onBack: () -> Unit,
  onOpenTool: (AhipTool) -> Unit,
  onContinueDraft: (AhipDraft) -> Unit,
  onDeleteDraft: (AhipDraft) -> Unit,
  onReviewDraft: (AhipDraft) -> Unit,
) {
  var draftToDelete by remember { mutableStateOf<AhipDraft?>(null) }
  draftToDelete?.let { draft ->
    AlertDialog(
      onDismissRequest = { draftToDelete = null },
      title = { Text(stringResource(R.string.ahip_delete_draft_title)) },
      text = { Text(stringResource(R.string.ahip_delete_draft_message)) },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteDraft(draft)
            draftToDelete = null
          },
        ) {
          Text(stringResource(R.string.ahip_delete), color = Color(0xFFC62828))
        }
      },
      dismissButton = {
        TextButton(onClick = { draftToDelete = null }) {
          Text(stringResource(R.string.ahip_cancel), color = AhipColors.Navy)
        }
      },
    )
  }
  AhipScaffold(
    title = stringResource(R.string.ahip_assessment_label, assessment.title, assessment.period),
    onBack = onBack,
  ) { modifier ->
    LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (drafts.isNotEmpty()) {
        item { ScreenHeading(stringResource(R.string.ahip_drafts_heading, drafts.size)) }
        items(drafts, key = { "draft-${it.responseId}" }) { draft ->
          DraftCard(
            draft = draft,
            onContinue = { onContinueDraft(draft) },
            onDelete = { draftToDelete = draft },
            onReview = { onReviewDraft(draft) },
          )
        }
        item { Spacer(Modifier.height(6.dp)) }
      }
      item {
        ScreenHeading(stringResource(R.string.ahip_assessment_tools, AhipCatalog.tools.size))
      }
      items(AhipCatalog.tools, key = { it.number }) { tool ->
        val details = toolDetails[tool.questionnaireId]
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .clip(CardShape)
              .background(Color.White)
              .border(BorderStroke(1.dp, AhipColors.Border), CardShape)
              .clickable { onOpenTool(tool) }
              .padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ToolIconTile(tool)
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(
              stringResource(R.string.ahip_tool_title, tool.number, tool.title),
              fontWeight = FontWeight.Bold,
              color = AhipColors.Navy,
            )
            if (details != null) {
              Text(
                "(" +
                  pluralStringResource(
                    R.plurals.ahip_question_count,
                    details.questionCount,
                    details.questionCount,
                  ) +
                  ")",
                color = AhipColors.TextMuted,
                fontSize = 13.sp,
              )
            }
          }
          Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AhipColors.TextMuted,
          )
        }
      }
    }
  }
}

/** An unfinished form: which tool, where, how far along, and when it was saved. */
@Composable
private fun DraftCard(
  draft: AhipDraft,
  onContinue: () -> Unit,
  onDelete: () -> Unit,
  onReview: () -> Unit,
) {
  val saved =
    draft.savedAt?.let {
      java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(it)
    } ?: "–"
  Column(
    Modifier.fillMaxWidth()
      .clip(CardShape)
      .background(AhipColors.PurpleTint)
      .border(BorderStroke(1.dp, AhipColors.Purple.copy(alpha = 0.35f)), CardShape)
      .clickable(onClick = onContinue)
      .padding(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      ToolIconTile(draft.tool, size = 40)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(
          stringResource(R.string.ahip_tool_title, draft.tool.number, draft.tool.title),
          fontWeight = FontWeight.Bold,
          color = AhipColors.Navy,
        )
        Text(
          draft.site ?: stringResource(R.string.ahip_draft_no_site),
          color = AhipColors.Navy,
          fontSize = 14.sp,
        )
        Text(
          pluralStringResource(
            R.plurals.ahip_draft_progress,
            draft.answeredCount,
            draft.answeredCount,
            saved,
          ),
          color = AhipColors.TextMuted,
          fontSize = 13.sp,
        )
      }
    }
    Row(
      Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(onClick = onDelete) {
        Text(stringResource(R.string.ahip_delete), color = AhipColors.TextMuted)
      }
      OutlinedButton(
        onClick = onReview,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, AhipColors.Purple),
      ) {
        Text(stringResource(R.string.ahip_review_edit), color = AhipColors.Purple, fontWeight = FontWeight.SemiBold)
      }
      Spacer(Modifier.width(8.dp))
      Button(
        onClick = onContinue,
        shape = RoundedCornerShape(20.dp),
        colors =
          ButtonDefaults.buttonColors(backgroundColor = AhipColors.Purple, contentColor = Color.White),
        elevation = null,
      ) {
        Text(stringResource(R.string.ahip_continue_draft), fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

@Composable
internal fun ToolIconTile(tool: AhipTool, size: Int = 48) {
  val tileColor = if (tool.number % 2 == 1) AhipColors.Navy else AhipColors.Green
  Box(
    modifier = Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(tileColor),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      tool.icon,
      contentDescription = null,
      tint = Color.White,
      modifier = Modifier.size((size * 0.6).dp),
    )
  }
}

// Screen 6: Tool detail

@Composable
fun AhipToolDetailScreen(
  tool: AhipTool,
  details: AhipToolDetails?,
  onBack: () -> Unit,
  onStart: () -> Unit,
) {
  AhipScaffold(
    title = stringResource(R.string.ahip_tool_title, tool.number, tool.title),
    onBack = onBack,
    bottomBar = {
      AhipBottomBar {
        AhipPrimaryButton(
          text = stringResource(R.string.ahip_start_assessment),
          onClick = onStart,
          enabled = details != null,
        )
      }
    },
  ) { modifier ->
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
      Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ToolIconTile(tool, size = 72)
        Spacer(Modifier.height(12.dp))
        Text(
          stringResource(R.string.ahip_tool_title, tool.number, tool.title),
          fontWeight = FontWeight.Bold,
          fontSize = 20.sp,
          color = AhipColors.Navy,
          textAlign = TextAlign.Center,
        )
      }
      Spacer(Modifier.height(20.dp))
      if (details == null) {
        Text(stringResource(R.string.ahip_form_not_loaded), color = AhipColors.Purple)
      } else {
        FactRow(
          Icons.AutoMirrored.Outlined.HelpOutline,
          stringResource(R.string.ahip_questions_label, details.questionCount),
        )
      }
      FactRow(Icons.Outlined.Schedule, stringResource(R.string.ahip_estimated_time, tool.estimatedMinutes))
      FactRow(Icons.Outlined.Groups, stringResource(R.string.ahip_target, tool.target))
      if (!details?.sections.isNullOrEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
          stringResource(R.string.ahip_sections),
          fontWeight = FontWeight.Bold,
          fontSize = 17.sp,
          color = AhipColors.Navy,
        )
        Spacer(Modifier.height(8.dp))
        details?.sections?.forEach { section ->
          Row(
            Modifier.fillMaxWidth()
              .padding(vertical = 3.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(Color.White)
              .border(BorderStroke(1.dp, AhipColors.Border), RoundedCornerShape(8.dp))
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(section, color = AhipColors.Navy, modifier = Modifier.weight(1f))
          }
        }
      }
    }
  }
}

@Composable
private fun FactRow(icon: ImageVector, text: String) {
  Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, contentDescription = null, tint = AhipColors.Navy, modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(12.dp))
    Text(text, color = AhipColors.Navy)
  }
}

// Screen 8: Submission

/** The quiz result on the "submitted" screen: points, percentage and whether time ran out. */
@Composable
private fun QuizScoreCard(score: AhipQuizScore) {
  val good = score.percent >= 50
  Column(
    Modifier.fillMaxWidth()
      .padding(bottom = 16.dp)
      .background(if (good) Color(0xFFE6F4EA) else Color(0xFFFDECEC), RoundedCornerShape(16.dp))
      .padding(20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(stringResource(R.string.ahip_quiz_score_title), color = AhipColors.Navy, fontWeight = FontWeight.SemiBold)
    Text(
      "${score.points} / ${score.total}",
      fontSize = 40.sp,
      fontWeight = FontWeight.Bold,
      color = if (good) Color(0xFF1E6B2E) else Color(0xFF8A1C1C),
    )
    Text(stringResource(R.string.ahip_quiz_score_detail, score.percent, score.right, score.count), color = AhipColors.Navy)
    score.seconds?.let {
      Text(stringResource(R.string.ahip_quiz_time_taken, it / 60, it % 60), color = Color.Gray, fontSize = 13.sp)
    }
    if (score.timedOut) {
      Text(stringResource(R.string.ahip_quiz_timed_out), color = Color(0xFF8A1C1C), fontSize = 13.sp)
    }
  }
}

@Composable
fun AhipSubmissionScreen(
  tool: AhipTool,
  submission: AhipSubmission?,
  syncStatus: AhipSyncStatus,
  onSync: () -> Unit,
  onSaveAndExit: () -> Unit,
) {
  AhipScaffold(title = stringResource(R.string.ahip_tool_title, tool.number, tool.title), onBack = onSaveAndExit) {
    modifier ->
    Column(
      modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        Icons.Filled.CloudUpload,
        contentDescription = null,
        tint = AhipColors.Purple,
        modifier = Modifier.size(96.dp),
      )
      Text(
        stringResource(R.string.ahip_assessment_completed),
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = AhipColors.Purple,
      )
      Spacer(Modifier.height(20.dp))
      Column(Modifier.fillMaxWidth()) {
        when {
          submission == null ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(color = AhipColors.Purple, modifier = Modifier.size(24.dp))
              Spacer(Modifier.width(12.dp))
              Text(stringResource(R.string.ahip_saving), color = AhipColors.Navy)
            }
          submission.saved -> {
            submission.quiz?.let { QuizScoreCard(it) }
            val answered = submission.answeredCount
            CheckRow(pluralStringResource(R.plurals.ahip_answered_count, answered, answered))
            if (submission.photoCount > 0) {
              CheckRow(stringResource(R.string.ahip_photos_attached, submission.photoCount))
            }
            if (submission.gpsCaptured) CheckRow(stringResource(R.string.ahip_gps_captured))
            if (submission.audioCount > 0) {
              CheckRow(stringResource(R.string.ahip_audio_attached, submission.audioCount))
            }
            CheckRow(stringResource(R.string.ahip_saved_on_device))
            CheckRow(stringResource(R.string.ahip_data_validated))
            CheckRow(stringResource(R.string.ahip_ready_to_sync))
          }
          else -> StatusText(false, stringResource(R.string.ahip_save_failed))
        }
      }
      Spacer(Modifier.height(28.dp))
      when (syncStatus) {
        AhipSyncStatus.RUNNING ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = AhipColors.Purple, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.ahip_syncing), color = AhipColors.Navy)
          }
        AhipSyncStatus.SUCCEEDED -> StatusText(true, stringResource(R.string.ahip_sync_succeeded))
        AhipSyncStatus.FAILED -> StatusText(false, stringResource(R.string.ahip_sync_failed))
        AhipSyncStatus.NO_SERVER -> StatusText(false, stringResource(R.string.ahip_server_missing))
        AhipSyncStatus.IDLE -> Unit
      }
      Spacer(Modifier.height(16.dp))
      AhipPrimaryButton(
        text = stringResource(R.string.ahip_sync_to_database),
        onClick = onSync,
        color = AhipColors.Purple,
        enabled = syncStatus != AhipSyncStatus.RUNNING,
        leadingIcon = Icons.Filled.CloudUpload,
      )
      Spacer(Modifier.height(12.dp))
      OutlinedButton(
        onClick = onSaveAndExit,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, AhipColors.Navy),
      ) {
        Text(
          stringResource(R.string.ahip_save_and_exit),
          color = AhipColors.Navy,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun CheckRow(text: String) {
  Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AhipColors.Green)
    Spacer(Modifier.width(12.dp))
    Text(text, color = AhipColors.Navy, fontSize = 16.sp)
  }
}

@Composable
internal fun StatusText(success: Boolean, text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(
      if (success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
      contentDescription = null,
      tint = if (success) AhipColors.Green else AhipColors.Purple,
    )
    Spacer(Modifier.width(8.dp))
    Text(text, color = AhipColors.Navy)
  }
}

// Draft review: every question with its answer and an Edit button that opens the form there.

@Composable
fun AhipDraftReviewScreen(
  draft: AhipDraft,
  lines: List<AhipDraftLine>?,
  onBack: () -> Unit,
  onEdit: (AhipDraftLine) -> Unit,
  onContinue: () -> Unit,
) {
  var onlyMissing by remember { mutableStateOf(false) }
  AhipScaffold(
    title = stringResource(R.string.ahip_draft_review_title),
    onBack = onBack,
    bottomBar = {
      AhipBottomBar {
        AhipPrimaryButton(
          text = stringResource(R.string.ahip_continue_draft_from_start),
          onClick = onContinue,
          color = AhipColors.Purple,
        )
      }
    },
  ) { modifier ->
    if (lines == null) {
      Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AhipColors.Purple)
      }
      return@AhipScaffold
    }
    val answered = lines.count { it.answer != null }
    val shown = if (onlyMissing) lines.filter { it.answer == null } else lines
    LazyColumn(
      modifier = modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Text(
          stringResource(R.string.ahip_tool_title, draft.tool.number, draft.tool.title),
          fontWeight = FontWeight.Bold,
          fontSize = 18.sp,
          color = AhipColors.Navy,
        )
        Text(
          (draft.site ?: stringResource(R.string.ahip_draft_no_site)) + " · " +
            stringResource(R.string.ahip_draft_answered_of, answered, lines.size),
          color = AhipColors.TextMuted,
          fontSize = 14.sp,
        )
        Row(
          Modifier.padding(top = 8.dp).clickable { onlyMissing = !onlyMissing },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          androidx.compose.material.Checkbox(
            checked = onlyMissing,
            onCheckedChange = { onlyMissing = it },
            colors = androidx.compose.material.CheckboxDefaults.colors(checkedColor = AhipColors.Purple),
          )
          Text(stringResource(R.string.ahip_draft_only_missing), color = AhipColors.Navy)
        }
      }
      var lastSection: String? = null
      shown.forEach { line ->
        if (line.section.isNotBlank() && line.section != lastSection) {
          lastSection = line.section
          item(key = "section-${line.linkId}") {
            Text(
              line.section,
              fontWeight = FontWeight.Bold,
              color = AhipColors.Purple,
              modifier = Modifier.padding(top = 10.dp),
            )
          }
        }
        item(key = line.linkId) { DraftLineCard(line, onEdit = { onEdit(line) }) }
      }
    }
  }
}

@Composable
private fun DraftLineCard(line: AhipDraftLine, onEdit: () -> Unit) {
  val missing = line.answer == null
  Row(
    Modifier.fillMaxWidth()
      .clip(CardShape)
      .background(if (missing) Color(0xFFFFF8EC) else Color.White)
      .border(BorderStroke(1.dp, if (missing) Color(0xFFF3D48A) else AhipColors.Border), CardShape)
      .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        line.label + if (line.required) " *" else "",
        color = AhipColors.Navy,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
      )
      Text(
        line.answer ?: stringResource(R.string.ahip_draft_not_answered),
        color = if (missing) Color(0xFF8A5300) else AhipColors.TextMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
    TextButton(onClick = onEdit) {
      Icon(Icons.Outlined.Edit, contentDescription = null, tint = AhipColors.Purple, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(4.dp))
      Text(stringResource(R.string.ahip_edit), color = AhipColors.Purple, fontWeight = FontWeight.SemiBold)
    }
  }
}

// "Share my data": create a personal API key so other tools can read this collector's data.

@Composable
fun AhipShareDataDialog(
  state: AhipShareState,
  onCreate: (String) -> Unit,
  onDismiss: () -> Unit,
  onShare: (String) -> Unit,
) {
  val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
  var password by remember { mutableStateOf("") }
  var copied by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.ahip_share_data), fontWeight = FontWeight.Bold, color = AhipColors.Navy) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (state) {
          is AhipShareState.Ready -> {
            Text(stringResource(R.string.ahip_share_ready), fontSize = 14.sp)
            Text(
              state.apiKey,
              fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
              fontSize = 13.sp,
              color = AhipColors.Navy,
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AhipColors.Background).padding(10.dp),
            )
            Text(stringResource(R.string.ahip_share_links, state.jsonUrl, state.csvUrl), fontSize = 13.sp, color = AhipColors.TextMuted)
            if (copied) Text(stringResource(R.string.ahip_share_copied), color = AhipColors.Green, fontSize = 13.sp)
          }
          else -> {
            Text(stringResource(R.string.ahip_share_intro), fontSize = 14.sp)
            androidx.compose.material.OutlinedTextField(
              value = password,
              onValueChange = { password = it },
              label = { Text(stringResource(R.string.ahip_share_password)) },
              singleLine = true,
              visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
              modifier = Modifier.fillMaxWidth(),
            )
            if (state is AhipShareState.Error) Text(state.message, color = Color(0xFFC62828), fontSize = 13.sp)
            if (state is AhipShareState.Loading) CircularProgressIndicator(color = AhipColors.Purple, modifier = Modifier.size(24.dp))
          }
        }
      }
    },
    confirmButton = {
      if (state is AhipShareState.Ready) {
        val text = "AHIP Collect data – API key: ${state.apiKey}\nJSON: ${state.jsonUrl}\nCSV (Excel): ${state.csvUrl}\n" +
          "Send the key in the X-API-Key header, or add ?api_key=KEY to a link."
        Row {
          TextButton(onClick = {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.apiKey))
            copied = true
          }) { Text(stringResource(R.string.ahip_share_copy), color = AhipColors.Navy) }
          TextButton(onClick = { onShare(text) }) { Text(stringResource(R.string.ahip_share_send), color = AhipColors.Purple, fontWeight = FontWeight.Bold) }
        }
      } else {
        TextButton(enabled = password.isNotBlank() && state !is AhipShareState.Loading, onClick = { onCreate(password) }) {
          Text(stringResource(R.string.ahip_share_create), color = AhipColors.Purple, fontWeight = FontWeight.Bold)
        }
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ahip_cancel), color = AhipColors.TextMuted) } },
  )
}
