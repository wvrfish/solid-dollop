package com.example.app

/**
 * Platform-agnostic interface for Firebase operations.
 * Implemented in desktopMain using the Firebase Admin SDK.
 */
interface FirebaseService {
    /** Initialize the Firebase app. Must be called before any other operation. */
    fun initialize(credentialsPath: String, databaseUrl: String)

    /** Write a document to Firestore under [collection]/[documentId]. */
    suspend fun setDocument(collection: String, documentId: String, data: Map<String, Any>)

    /** Read a document from Firestore. Returns null if the document does not exist. */
    suspend fun getDocument(collection: String, documentId: String): Map<String, Any>?

    /** Delete a document from Firestore. */
    suspend fun deleteDocument(collection: String, documentId: String)
}
