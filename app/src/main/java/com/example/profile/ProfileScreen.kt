package com.example.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profileManager: ProfileManager,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val userProfile by profileManager.userProfileFlow.collectAsState(
        initial = UserProfile("", "", "", "", "", "")
    )

    var name by remember(userProfile.name) { mutableStateOf(userProfile.name) }
    var age by remember(userProfile.age) { mutableStateOf(userProfile.age) }
    var location by remember(userProfile.location) { mutableStateOf(userProfile.location) }
    var job by remember(userProfile.job) { mutableStateOf(userProfile.job) }
    var hobbies by remember(userProfile.hobbies) { mutableStateOf(userProfile.hobbies) }
    var regimen by remember(userProfile.regimen) { mutableStateOf(userProfile.regimen) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль Хозяина") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = age,
                onValueChange = { age = it },
                label = { Text("Возраст") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Локация") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = job,
                onValueChange = { job = it },
                label = { Text("Работа") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = hobbies,
                onValueChange = { hobbies = it },
                label = { Text("Хобби") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = regimen,
                onValueChange = { regimen = it },
                label = { Text("Режим") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    coroutineScope.launch {
                        profileManager.saveProfile(
                            UserProfile(name, age, location, job, hobbies, regimen)
                        )
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Text("Сохранить")
            }
        }
    }
}
