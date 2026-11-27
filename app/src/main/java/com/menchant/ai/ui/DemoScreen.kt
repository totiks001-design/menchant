package com.menchant.ai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.menchant.ai.data.SettingsStore
import com.menchant.ai.service.AgentService
import com.menchant.ai.service.AiAccessibilityService
import com.menchant.ai.service.DemoState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun notificationsGranted(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun DemoScreen(store: SettingsStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by store.demoEnabled.collectAsState(initial = false)
    val running by DemoState.running.collectAsState()
    val log by DemoState.log.collectAsState()

    var task by rememberSaveable { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val a11yOn = remember(tick) { AiAccessibilityService.instance != null }
    val overlayOn = remember(tick) { Settings.canDrawOverlays(ctx) }
    val notifOn = remember(tick) { notificationsGranted(ctx) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val logState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) logState.scrollToItem(log.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Демо: управление телефоном", style = MaterialTheme.typography.titleLarge)

        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                "ВНИМАНИЕ. В этом режиме ИИ сможет нажимать на экран, вводить текст и открывать " +
                    "приложения от вашего имени и видит всё, что показано на экране (текст экрана " +
                    "отправляется провайдеру модели). Не запускайте его при открытых банках, " +
                    "паролях, личной переписке. Поля паролей приложение не заполняет. Модель может " +
                    "ошибаться — следите за процессом и в любой момент нажмите STOP в уведомлении. " +
                    "Лимит — 20 шагов на задачу.",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Включить демо-режим", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = enabled,
                onCheckedChange = { v ->
                    scope.launch { store.setDemoEnabled(v) }
                    if (!v && running) AgentService.stop(ctx)
                }
            )
        }

        StatusRow(
            label = "Служба доступности",
            ok = a11yOn,
            actionLabel = "Открыть настройки"
        ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (!a11yOn) {
            Text(
                "Если переключатель неактивен («Ограниченная настройка»): Настройки → Приложения → " +
                    "Menchant → ⋮ → «Разрешить ограниченные настройки».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusRow(
            label = "Оверлей «Menchant управляет телефоном»",
            ok = overlayOn,
            actionLabel = "Выдать"
        ) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            )
        }
        StatusRow(
            label = "Уведомления (кнопка STOP)",
            ok = notifOn,
            actionLabel = "Разрешить"
        ) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        OutlinedTextField(
            value = task,
            onValueChange = { task = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Задача, например: открой Chrome") },
            enabled = !running,
            maxLines = 4
        )

        if (running) {
            Button(
                onClick = { AgentService.stop(ctx) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("STOP") }
        } else {
            Button(
                onClick = { AgentService.start(ctx, task.trim()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && a11yOn && overlayOn && notifOn && task.isNotBlank()
            ) { Text("Запустить") }
        }

        Text("Лог", style = MaterialTheme.typography.titleMedium)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            LazyColumn(state = logState, modifier = Modifier.padding(8.dp)) {
                items(log) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
        OutlinedButton(onClick = { DemoState.clear() }, enabled = !running) { Text("Очистить лог") }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (ok) "готово" else "не включено",
                style = MaterialTheme.typography.labelMedium,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        if (!ok) OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}