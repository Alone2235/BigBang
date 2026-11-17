package com.eta.bigbang.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { (id, label) ->
                    TextButton(
                        onClick = { onPick(id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            fontWeight = if (id == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (id == selected) Text("✓")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun AppPickerDialog(
    apps: List<AppEntry>,
    loading: Boolean,
    selected: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var local by remember { mutableStateOf(selected) }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择生效应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("搜索应用名或包名") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Text(
                        "已选 ${local.size} 个 · 可见 ${filtered.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.height(380.dp)) {
                        items(filtered) { app ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.pkg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = app.pkg in local,
                                    onCheckedChange = { on ->
                                        local = if (on) local + app.pkg else local - app.pkg
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(local) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
