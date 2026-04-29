package com.forge.bridge.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.bridge.data.local.entities.AuditLogEntity
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start the Bridge Service
        val serviceIntent = Intent(this, com.forge.bridge.service.ForgeBridgeService::class.java)
        startForegroundService(serviceIntent)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1E88E5),
                    secondary = Color(0xFF26A69A)
                )
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    BridgeDashboard()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeDashboard(viewModel: MainViewModel = hiltViewModel()) {
    val providers by viewModel.providers.collectAsState()
    val logs by viewModel.recentLogs.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ComponentActivity.RESULT_OK) {
            val token = result.data?.getStringExtra("token")
            val type = result.data?.getStringExtra("type") ?: "chatgpt-proxy"
            if (token != null) {
                viewModel.addProvider("Web Session ($type)", type, token)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Forge Bridge", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showLogs = !showLogs }) {
                        Icon(if (showLogs) Icons.Default.Settings else Icons.Default.History, contentDescription = "Toggle Logs")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Provider")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!showLogs) {
                // Dashboard View
                Card(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = MaterialTheme.shapes.extraSmall, color = Color.Green, modifier = Modifier.size(12.dp)) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Server Active: localhost:8745", fontSize = 14.sp)
                    }
                }

                Text("Active Providers", modifier = Modifier.padding(start = 16.dp, bottom = 8.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(providers) { provider ->
                        ListItem(
                            headlineContent = { Text(provider.name) },
                            supportingContent = { Text(provider.type.uppercase()) },
                            trailingContent = {
                                IconButton(onClick = { viewModel.deleteProvider(provider.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                // Logs View
                Text("Recent Requests", modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logs) { log ->
                        LogItem(log)
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddProviderDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, type, token ->
                viewModel.addProvider(name, type, token)
                showAddDialog = false
            },
            onAuthProxy = { type ->
                val url = when(type) {
                    "chatgpt-proxy" -> "https://chatgpt.com"
                    "claude-proxy" -> "https://claude.ai"
                    "gemini-proxy" -> "https://gemini.google.com"
                    else -> "https://chatgpt.com"
                }
                val intent = Intent(context, ProxyAuthActivity::class.java).apply {
                    putExtra("url", url)
                    putExtra("type", type)
                }
                authLauncher.launch(intent)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun LogItem(log: AuditLogEntity) {
    val date = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
    ListItem(
        headlineContent = { Text("${log.provider} (${log.model})") },
        supportingContent = { Text("$date • ${log.messageCount} messages") },
        trailingContent = {
            Text(
                log.status.uppercase(),
                color = if (log.status == "success") Color(0xFF43A047) else Color.Red,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    )
}

@Composable
fun AddProviderDialog(
    onDismiss: () -> Unit, 
    onConfirm: (String, String, String) -> Unit,
    onAuthProxy: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("openai") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Provider") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (e.g. My AI)") })
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Official API", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row {
                    Button(onClick = { type = "openai" }, modifier = Modifier.weight(1f), 
                        colors = if(type == "openai") ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) { Text("GPT") }
                    Spacer(modifier = Modifier.width(2.dp))
                    Button(onClick = { type = "anthropic" }, modifier = Modifier.weight(1f),
                        colors = if(type == "anthropic") ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) { Text("Claude") }
                    Spacer(modifier = Modifier.width(2.dp))
                    Button(onClick = { type = "gemini" }, modifier = Modifier.weight(1f),
                        colors = if(type == "gemini") ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) { Text("Gemini") }
                }
                
                if (type == "openai" || type == "anthropic" || type == "gemini") {
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Web Proxy (Tier 2)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row {
                    OutlinedButton(onClick = { onAuthProxy("chatgpt-proxy") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("ChatGPT") }
                    Spacer(modifier = Modifier.width(2.dp))
                    OutlinedButton(onClick = { onAuthProxy("claude-proxy") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("Claude") }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Browser Automation (Tier 3)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row {
                    OutlinedButton(onClick = { onConfirm("ChatGPT Browser", "browser-tier", "https://chatgpt.com") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("ChatGPT") }
                    Spacer(modifier = Modifier.width(2.dp))
                    OutlinedButton(onClick = { onConfirm("Claude Browser", "browser-tier", "https://claude.ai") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("Claude") }
                    Spacer(modifier = Modifier.width(2.dp))
                    OutlinedButton(onClick = { onConfirm("Gemini Browser", "browser-tier", "https://gemini.google.com") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("Gemini") }
                }
            }
        },
        confirmButton = {
            if (type == "openai" || type == "anthropic" || type == "gemini") {
                TextButton(onClick = { onConfirm(name, type, token) }) { Text("Save API Key") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
