package com.example.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun App(firebase: FirebaseService) {
    MaterialTheme {
        FirestoreScreen(firebase)
    }
}

@Composable
private fun FirestoreScreen(firebase: FirebaseService) {
    val scope = rememberCoroutineScope()

    var docId by remember { mutableStateOf("") }
    var fieldKey by remember { mutableStateOf("") }
    var fieldValue by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var fetchedDoc by remember { mutableStateOf<Map<String, Any>?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Firebase Firestore Demo", style = MaterialTheme.typography.headlineMedium)

        HorizontalDivider()

        OutlinedTextField(
            value = docId,
            onValueChange = { docId = it },
            label = { Text("Document ID") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fieldKey,
                onValueChange = { fieldKey = it },
                label = { Text("Field key") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                label = { Text("Field value") },
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (docId.isBlank() || fieldKey.isBlank()) {
                        statusMessage = "Document ID and field key are required."
                        return@Button
                    }
                    scope.launch {
                        isLoading = true
                        runCatching {
                            firebase.setDocument(
                                collection = "demo",
                                documentId = docId,
                                data = mapOf(fieldKey to fieldValue)
                            )
                            statusMessage = "Written: demo/$docId"
                        }.onFailure { statusMessage = "Error: ${it.message}" }
                        isLoading = false
                    }
                },
                enabled = !isLoading
            ) { Text("Write") }

            OutlinedButton(
                onClick = {
                    if (docId.isBlank()) {
                        statusMessage = "Document ID is required."
                        return@OutlinedButton
                    }
                    scope.launch {
                        isLoading = true
                        runCatching {
                            fetchedDoc = firebase.getDocument("demo", docId)
                            statusMessage = if (fetchedDoc != null) "Fetched demo/$docId" else "Document not found."
                        }.onFailure { statusMessage = "Error: ${it.message}" }
                        isLoading = false
                    }
                },
                enabled = !isLoading
            ) { Text("Read") }

            OutlinedButton(
                onClick = {
                    if (docId.isBlank()) {
                        statusMessage = "Document ID is required."
                        return@OutlinedButton
                    }
                    scope.launch {
                        isLoading = true
                        runCatching {
                            firebase.deleteDocument("demo", docId)
                            fetchedDoc = null
                            statusMessage = "Deleted demo/$docId"
                        }.onFailure { statusMessage = "Error: ${it.message}" }
                        isLoading = false
                    }
                },
                enabled = !isLoading
            ) { Text("Delete") }
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (statusMessage.isNotBlank()) {
            Text(statusMessage, color = MaterialTheme.colorScheme.primary)
        }

        fetchedDoc?.let { doc ->
            HorizontalDivider()
            Text("Document fields:", style = MaterialTheme.typography.titleSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(doc.entries.toList()) { (k, v) ->
                    Text("  $k = $v")
                }
            }
        }
    }
}
