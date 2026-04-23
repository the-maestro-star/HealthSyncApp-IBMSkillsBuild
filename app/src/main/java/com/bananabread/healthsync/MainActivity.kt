package com.bananabread.healthsync

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bananabread.healthsync.data.Appointment
import com.bananabread.healthsync.data.Medication
import com.bananabread.healthsync.ui.components.CameraCapture
import com.bananabread.healthsync.ui.theme.HealthSyncTheme
import com.bananabread.healthsync.ui.viewmodel.ChatMessage
import com.bananabread.healthsync.ui.viewmodel.MainViewModel
import com.bananabread.healthsync.ui.viewmodel.Screen
import com.bananabread.healthsync.util.CalendarSync
import com.bananabread.healthsync.util.NotificationHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthSyncTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentScreen by viewModel.currentScreen.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var showCamera by remember { mutableStateOf(false) }
    val capturedImages by viewModel.capturedImages.collectAsState()
    var showErrorDialog by remember { mutableStateOf(false) }
    var pendingSyncAppointments by remember { mutableStateOf<List<Appointment>>(emptyList()) }
    var pendingSyncMedications by remember { mutableStateOf<List<Medication>>(emptyList()) }

    val calendarSync = remember { CalendarSync(context) }
    val notificationHelper = remember { NotificationHelper(context) }

    val cameraPermissions = remember { arrayOf(Manifest.permission.CAMERA) }
    val calendarPermissions = remember {
        arrayOf(
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CALENDAR
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.CAMERA] == true) showCamera = true
    }
    val performCalendarSync: (List<Appointment>, List<Medication>) -> Unit = remember(calendarSync, notificationHelper, context) {
        { appointments, medications ->
            var success = 0
            appointments.forEach { if (calendarSync.addAppointmentToCalendar(it)) success++ }
            medications.forEach { if (calendarSync.addMedicationToCalendar(it)) success++ }
            if (success > 0) {
                notificationHelper.showNotification("Calendar Sync", "$success items added to your schedule.")
                Toast.makeText(context, "Successfully synced to your calendar.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Sync failed. Ensure a writable calendar is available.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val allGranted = calendarPermissions.all { results[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (allGranted && (pendingSyncAppointments.isNotEmpty() || pendingSyncMedications.isNotEmpty())) {
            performCalendarSync(pendingSyncAppointments, pendingSyncMedications)
        }
        pendingSyncAppointments = emptyList()
        pendingSyncMedications = emptyList()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) showErrorDialog = true
    }

    if (showErrorDialog && errorMessage != null) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false; viewModel.clearError() },
            title = { Text("HealthSync Assistant", fontWeight = FontWeight.Bold) },
            text = { Text(errorMessage!!) },
            confirmButton = { Button(onClick = { showErrorDialog = false; viewModel.clearError() }) { Text("Got it") } },
            dismissButton = {
                TextButton(onClick = {
                    val clip = ClipData.newPlainText("AI Status", errorMessage)
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy Error") }
            }
        )
    }

    if (showCamera) {
        CameraCapture(
            capturedImages = capturedImages,
            onImageCaptured = { viewModel.addCapturedImage(it) },
            onRemoveImage = { viewModel.removeImage(it) },
            onFinish = {
                viewModel.scanCapturedImages()
                showCamera = false
            },
            onClose = { 
                viewModel.clearImages()
                showCamera = false 
            }
        )
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Color.White,
                    modifier = Modifier.width(300.dp)
                ) {
                    SidebarContent(viewModel, drawerState)
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Healing, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                val title = when(currentScreen) {
                                    Screen.Dashboard -> "Dashboard"
                                    Screen.Chatbot -> "AI Assistant"
                                    Screen.Schedule -> "My Schedule"
                                    Screen.Medications -> "Medications"
                                    Screen.Settings -> "Settings"
                                }
                                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, "Menu")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
                    )
                },
                floatingActionButton = {
                    if (currentScreen == Screen.Dashboard) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                val cameraGranted = cameraPermissions.all {
                                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                }
                                if (cameraGranted) {
                                    showCamera = true
                                } else {
                                    cameraPermissionLauncher.launch(cameraPermissions)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White,
                            icon = { Icon(Icons.Default.QrCodeScanner, null) },
                            text = { Text("Scan Discharge", fontWeight = FontWeight.Bold) },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (currentScreen) {
                        Screen.Dashboard -> DashboardScreen(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FA)).padding(horizontal = 24.dp),
                            onSync = { appts, meds ->
                                val calendarPerms = arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
                                val allGranted = calendarPerms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                                
                                if (allGranted) {
                                    performCalendarSync(appts, meds)
                                } else {
                                    pendingSyncAppointments = appts
                                    pendingSyncMedications = meds
                                    calendarPermissionLauncher.launch(calendarPermissions)
                                }
                            }
                        )
                        Screen.Chatbot -> ChatbotScreen(viewModel)
                        Screen.Schedule -> ScheduleScreen(viewModel)
                        Screen.Medications -> MedicationsScreen(viewModel)
                        Screen.Settings -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Settings coming soon") }
                    }
                }
            }
        }
    }
}

@Composable
fun SidebarContent(viewModel: MainViewModel, drawerState: DrawerState) {
    val scope = rememberCoroutineScope()
    val currentScreen by viewModel.currentScreen.collectAsState()

    Column(modifier = Modifier.padding(24.dp)) {
        Box(modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Healing, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("HealthSync AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Text("Recovery simplified.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(40.dp))
        
        SidebarItem(Icons.Default.Dashboard, "Dashboard", currentScreen == Screen.Dashboard) {
            viewModel.navigateTo(Screen.Dashboard)
            scope.launch { drawerState.close() }
        }
        SidebarItem(Icons.AutoMirrored.Filled.Chat, "AI Assistant", currentScreen == Screen.Chatbot) {
            viewModel.navigateTo(Screen.Chatbot)
            scope.launch { drawerState.close() }
        }
        SidebarItem(Icons.Default.CalendarMonth, "My Schedule", currentScreen == Screen.Schedule) {
            viewModel.navigateTo(Screen.Schedule)
            scope.launch { drawerState.close() }
        }
        SidebarItem(Icons.Default.Medication, "Medications", currentScreen == Screen.Medications) {
            viewModel.navigateTo(Screen.Medications)
            scope.launch { drawerState.close() }
        }
        
        Spacer(Modifier.weight(1f))
        SidebarItem(Icons.Default.Settings, "Settings", currentScreen == Screen.Settings) {
            viewModel.navigateTo(Screen.Settings)
            scope.launch { drawerState.close() }
        }
    }
}

@Composable
fun SidebarItem(icon: ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(0.1f) else Color.Transparent)
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
fun DashboardScreen(
    viewModel: MainViewModel, 
    modifier: Modifier, 
    onSync: (List<Appointment>, List<Medication>) -> Unit
) {
    val medications by viewModel.medications.collectAsState()
    val appointments by viewModel.appointments.collectAsState()
    val healthSummary by viewModel.healthSummary.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val date = remember { SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date()) }

    Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Spacer(Modifier.height(8.dp))
        
        Column {
            Text("Hi there!", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2D3436))
            Text(date, style = MaterialTheme.typography.titleMedium, color = Color(0xFF636E72))
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryBox(Modifier.weight(1f), medications.size.toString(), "Meds Today", Icons.Default.MedicalInformation, Color(0xFF4CAF50))
            SummaryBox(Modifier.weight(1f), appointments.size.toString(), "Next Events", Icons.AutoMirrored.Filled.EventNote, Color(0xFF2196F3))
        }

        if (isScanning) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(0.15f)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("AI is reading your notes...", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (healthSummary.diagnosis.isNotEmpty()) {
            InfoCard("Your Diagnosis", healthSummary.diagnosis, Icons.Default.Description, Color(0xFFE91E63))
        }

        if (healthSummary.planOverview.isNotEmpty()) {
            InfoCard("Your Recovery Plan", healthSummary.planOverview, Icons.AutoMirrored.Filled.Assignment, Color(0xFF9C27B0))
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today's Medications", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (medications.isNotEmpty() || appointments.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onSync(appointments, medications) }) {
                        Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sync All")
                    }
                }
            }
            if (medications.isEmpty()) {
                PlaceholderCard("Scan documents to see your medications.")
            } else {
                medications.forEach {
                    MedCard(
                        med = it,
                        showCheckbox = true,
                        mode = "dashboard",
                        onMedicationCheckedChange = viewModel::updateMedicationTaken
                    )
                }
            }
        }
        
        Spacer(Modifier.height(140.dp))
    }
}

@Composable
fun ChatbotScreen(viewModel: MainViewModel) {
    val messages by viewModel.chatMessages.collectAsState()
    var inputText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Color(0xFFF8F9FA)).padding(16.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages) { msg ->
                ChatBubble(msg)
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about your recovery...") },
                shape = RoundedCornerShape(24.dp),
                textStyle = TextStyle(color = Color.Black),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White,
                    focusedContainerColor = Color.White,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { 
                    viewModel.sendMessage(inputText)
                    inputText = "" 
                },
                modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White)
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    val color = if (message.isUser) MaterialTheme.colorScheme.primary else Color.White
    val textColor = if (message.isUser) Color.White else Color.Black
    val shape = if (message.isUser) 
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp) else 
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)

    Column(horizontalAlignment = alignment, modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = color,
            shape = shape,
            shadowElevation = 1.dp
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(12.dp),
                color = textColor,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun ScheduleScreen(viewModel: MainViewModel) {
    val appointments by viewModel.appointments.collectAsState()
    val medications by viewModel.medications.collectAsState()

    Column(Modifier.fillMaxSize().background(Color(0xFFF8F9FA)).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Daily Schedule", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        
        if (appointments.isEmpty() && medications.isEmpty()) {
            PlaceholderCard("No schedule data available.")
        } else {
            Text("Appointments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            appointments.forEach { ApptCard(it) }
            Spacer(Modifier.height(8.dp))
            Text("Medication Times (Daily)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            medications.forEach {
                MedCard(
                    med = it,
                    showCheckbox = false,
                    mode = "schedule",
                    onMedicationCheckedChange = viewModel::updateMedicationTaken
                )
            }
        }
    }
}

@Composable
fun MedicationsScreen(viewModel: MainViewModel) {
    val medications by viewModel.medications.collectAsState()

    Column(Modifier.fillMaxSize().background(Color(0xFFF8F9FA)).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("My Medications", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        
        if (medications.isEmpty()) {
            PlaceholderCard("No medications found.")
        } else {
            medications.forEach {
                MedCard(
                    med = it,
                    showCheckbox = false,
                    mode = "medications",
                    onMedicationCheckedChange = viewModel::updateMedicationTaken
                )
            }
        }
    }
}

@Composable
fun InfoCard(title: String, content: String, icon: ImageVector, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(24.dp)),
        color = Color.White,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(color.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF2D3436))
            }
            Spacer(Modifier.height(16.dp))
            Text(content, style = MaterialTheme.typography.bodyLarge, color = Color(0xFF486581), lineHeight = 24.sp)
        }
    }
}

@Composable
fun SummaryBox(modifier: Modifier, value: String, label: String, icon: ImageVector, color: Color) {
    Surface(modifier = modifier.shadow(2.dp, RoundedCornerShape(28.dp)), color = Color.White, shape = RoundedCornerShape(28.dp)) {
        Column(modifier = Modifier.padding(24.dp)) {
            Box(Modifier.background(color.copy(0.1f), CircleShape).padding(10.dp)) {
                Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(value, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2D3436))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF636E72))
        }
    }
}

@Composable
fun ApptCard(appt: Appointment) {
    Surface(modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(24.dp)), color = Color.White, shape = RoundedCornerShape(24.dp)) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(Color(0xFFE3F2FD), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, null, tint = Color(0xFF2196F3))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(appt.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF2D3436))
                Text(appt.doctorName, style = MaterialTheme.typography.bodySmall, color = Color(0xFF636E72))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(appt.time, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                Text(appt.date, style = MaterialTheme.typography.labelSmall, color = Color(0xFF636E72))
            }
        }
    }
}

@Composable
fun MedCard(
    med: Medication,
    showCheckbox: Boolean,
    mode: String,
    onMedicationCheckedChange: (String, Boolean) -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(24.dp)), color = Color.White, shape = RoundedCornerShape(24.dp)) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(Color(0xFFE8F5E9), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Medication, null, tint = Color(0xFF4CAF50))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (mode == "dashboard") {
                    val scheduleLabel = med.scheduleTimes.joinToString(", ").ifEmpty { "time not found" }
                    Text("Take ${med.name} at $scheduleLabel", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF2D3436))
                } else if (mode == "medications") {
                    Text(med.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF2D3436))
                    if (med.dosage.isNotEmpty() || med.scheduleTimes.isNotEmpty()) {
                        Text(
                            listOf(med.dosage, med.scheduleTimes.joinToString(", "))
                                .filter { it.isNotBlank() }
                                .joinToString(" - "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF636E72)
                        )
                    }
                } else {
                    Text(med.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF2D3436))
                    Text(
                        listOf(med.dosage, med.scheduleTimes.joinToString(", "))
                            .filter { it.isNotBlank() }
                            .joinToString(" - "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF636E72)
                    )
                }
            }
            if (showCheckbox) {
                Checkbox(
                    checked = med.isTaken,
                    onCheckedChange = { onMedicationCheckedChange(med.id, it) },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF4CAF50))
                )
            } else if (mode == "schedule") {
                Text(med.scheduleTimes.joinToString(", "), fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
            }
        }
    }
}

@Composable
fun PlaceholderCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray.copy(0.3f))
    ) {
        Box(Modifier.padding(40.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Color.Gray, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
