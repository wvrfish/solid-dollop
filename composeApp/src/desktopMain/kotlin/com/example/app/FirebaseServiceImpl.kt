package com.example.app

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

/**
 * Desktop (JVM) implementation of [FirebaseService] using the Firebase Admin SDK.
 *
 * Credentials: download a service-account JSON from the Firebase console
 * (Project Settings → Service accounts → Generate new private key) and pass
 * the file path to [initialize].
 */
class FirebaseServiceImpl : FirebaseService {

    private lateinit var firestore: Firestore

    override fun initialize(credentialsPath: String, databaseUrl: String) {
        if (FirebaseApp.getApps().isNotEmpty()) return  // already initialized

        val credentials = FileInputStream(credentialsPath).use { stream ->
            GoogleCredentials.fromStream(stream)
        }
        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .setDatabaseUrl(databaseUrl)
            .build()

        FirebaseApp.initializeApp(options)
        firestore = FirestoreClient.getFirestore()
    }

    override suspend fun setDocument(
        collection: String,
        documentId: String,
        data: Map<String, Any>
    ) = withContext(Dispatchers.IO) {
        firestore.collection(collection).document(documentId).set(data).get()
        Unit
    }

    override suspend fun getDocument(
        collection: String,
        documentId: String
    ): Map<String, Any>? = withContext(Dispatchers.IO) {
        val snapshot = firestore.collection(collection).document(documentId).get().get()
        if (snapshot.exists()) snapshot.data else null
    }

    override suspend fun deleteDocument(
        collection: String,
        documentId: String
    ) = withContext(Dispatchers.IO) {
        firestore.collection(collection).document(documentId).delete().get()
        Unit
    }
}
