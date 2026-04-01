package com.example.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    // Path to your Firebase service-account credentials JSON and your project's database URL.
    // These can also be supplied via environment variables:
    //   FIREBASE_CREDENTIALS_PATH  — path to the service-account JSON file
    //   FIREBASE_DATABASE_URL      — e.g. "https://<project-id>-default-rtdb.firebaseio.com"
    val credentialsPath = System.getenv("FIREBASE_CREDENTIALS_PATH")
        ?: "service-account.json"
    val databaseUrl = System.getenv("FIREBASE_DATABASE_URL")
        ?: "https://<your-project-id>-default-rtdb.firebaseio.com"

    val firebaseService: FirebaseService = FirebaseServiceImpl()

    runCatching {
        firebaseService.initialize(credentialsPath, databaseUrl)
    }.onFailure { e ->
        System.err.println(
            "Firebase initialization failed: ${e.message}\n" +
            "Set FIREBASE_CREDENTIALS_PATH and FIREBASE_DATABASE_URL environment variables, " +
            "or place service-account.json next to the executable."
        )
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "SolidDollop — Firebase Demo"
        ) {
            App(firebaseService)
        }
    }
}
