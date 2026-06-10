package com.codex.magicmirrorcontroller.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class LayoutPreset(
    val id: String = "",
    val name: String = "",
    val modules: List<PresetModule> = emptyList(),
    val createdAt: Long = 0L
)

data class PresetModule(
    val id: String = "",
    val visible: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 0f,
    val h: Float = 0f
)

class FirebaseSyncManager {
    private val tag = "FirebaseSyncManager"

    // Safe access to Firebase components to avoid crashes if google-services.json is missing
    val isFirebaseAvailable: Boolean by lazy {
        runCatching {
            FirebaseAuth.getInstance()
            FirebaseFirestore.getInstance()
        }.isSuccess
    }

    private val auth: FirebaseAuth?
        get() = if (isFirebaseAvailable) FirebaseAuth.getInstance() else null

    private val db: FirebaseFirestore?
        get() = if (isFirebaseAvailable) FirebaseFirestore.getInstance() else null

    val currentUser: FirebaseUser?
        get() = auth?.currentUser

    fun addAuthStateListener(listener: (FirebaseUser?) -> Unit) {
        auth?.addAuthStateListener { firebaseAuth ->
            listener(firebaseAuth.currentUser)
        }
    }

    suspend fun signIn(email: String, password: String): FirebaseUser {
        val authInstance = auth ?: throw IllegalStateException("Firebase is not initialized (check google-services.json)")
        return suspendCoroutine { continuation ->
            authInstance.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val user = task.result?.user
                        if (user != null) {
                            continuation.resume(user)
                        } else {
                            continuation.resumeWithException(Exception("Пользователь не найден"))
                        }
                    } else {
                        continuation.resumeWithException(task.exception ?: Exception("Ошибка авторизации"))
                    }
                }
        }
    }

    suspend fun signUp(email: String, password: String): FirebaseUser {
        val authInstance = auth ?: throw IllegalStateException("Firebase is not initialized (check google-services.json)")
        return suspendCoroutine { continuation ->
            authInstance.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val user = task.result?.user
                        if (user != null) {
                            continuation.resume(user)
                        } else {
                            continuation.resumeWithException(Exception("Пользователь не создан"))
                        }
                    } else {
                        continuation.resumeWithException(task.exception ?: Exception("Ошибка регистрации"))
                    }
                }
        }
    }

    fun signOut() {
        auth?.signOut()
    }

    // --- Server Config Sync ---

    suspend fun syncEndpointToCloud(config: ServerConfig) {
        val user = currentUser ?: return
        val dbInstance = db ?: return
        
        val data = mapOf(
            "host" to config.host,
            "port" to config.port,
            "token" to config.token,
            "updatedAt" to System.currentTimeMillis()
        )

        suspendCoroutine<Unit> { continuation ->
            dbInstance.collection("users")
                .document(user.uid)
                .collection("mirrors")
                .document("default")
                .set(data)
                .addOnSuccessListener {
                    Log.d(tag, "Config successfully synced to Firebase")
                    continuation.resume(Unit)
                }
                .addOnFailureListener { exception ->
                    Log.e(tag, "Failed to sync config to Firebase", exception)
                    continuation.resumeWithException(exception)
                }
        }
    }

    suspend fun fetchEndpointFromCloud(): ServerConfig? {
        val user = currentUser ?: return null
        val dbInstance = db ?: return null

        return suspendCoroutine { continuation ->
            dbInstance.collection("users")
                .document(user.uid)
                .collection("mirrors")
                .document("default")
                .get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val host = document.getString("host")
                        val port = document.getLong("port")?.toInt()
                        val token = document.getString("token")
                        if (host != null && port != null && token != null) {
                            continuation.resume(ServerConfig(host, port, token))
                        } else {
                            continuation.resume(null)
                        }
                    } else {
                        continuation.resume(null)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(tag, "Failed to fetch config from Firebase", exception)
                    continuation.resume(null) // fallback gracefully
                }
        }
    }

    // --- Layout Presets Sync ---

    suspend fun savePreset(name: String, modules: List<MirrorModule>): LayoutPreset {
        val user = currentUser ?: throw IllegalStateException("Пользователь не авторизован")
        val dbInstance = db ?: throw IllegalStateException("Firebase Firestore недоступен")

        val presetModules = modules.map { module ->
            mapOf(
                "id" to module.id,
                "visible" to module.visible,
                "x" to module.layout.x,
                "y" to module.layout.y,
                "w" to module.layout.w,
                "h" to module.layout.h
            )
        }

        val timestamp = System.currentTimeMillis()
        val ref = dbInstance.collection("users")
            .document(user.uid)
            .collection("presets")
            .document() // auto ID

        val data = mapOf(
            "id" to ref.id,
            "name" to name,
            "modules" to presetModules,
            "createdAt" to timestamp
        )

        return suspendCoroutine { continuation ->
            ref.set(data)
                .addOnSuccessListener {
                    val preset = LayoutPreset(
                        id = ref.id,
                        name = name,
                        modules = modules.map { m ->
                            PresetModule(m.id, m.visible, m.layout.x, m.layout.y, m.layout.w, m.layout.h)
                        },
                        createdAt = timestamp
                    )
                    continuation.resume(preset)
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        }
    }

    suspend fun fetchPresets(): List<LayoutPreset> {
        val user = currentUser ?: return emptyList()
        val dbInstance = db ?: return emptyList()

        return suspendCoroutine { continuation ->
            dbInstance.collection("users")
                .document(user.uid)
                .collection("presets")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener { snapshot ->
                    val list = mutableListOf<LayoutPreset>()
                    for (doc in snapshot.documents) {
                        val id = doc.id
                        val name = doc.getString("name") ?: ""
                        val createdAt = doc.getLong("createdAt") ?: 0L
                        val rawModules = doc.get("modules") as? List<Map<String, Any>> ?: emptyList()
                        
                        val modules = rawModules.map { map ->
                            PresetModule(
                                id = map["id"] as? String ?: "",
                                visible = map["visible"] as? Boolean ?: false,
                                x = (map["x"] as? Number)?.toFloat() ?: 0f,
                                y = (map["y"] as? Number)?.toFloat() ?: 0f,
                                w = (map["w"] as? Number)?.toFloat() ?: 0f,
                                h = (map["h"] as? Number)?.toFloat() ?: 0f
                            )
                        }
                        list.add(LayoutPreset(id, name, modules, createdAt))
                    }
                    continuation.resume(list)
                }
                .addOnFailureListener { exception ->
                    Log.e(tag, "Failed to fetch presets", exception)
                    continuation.resume(emptyList())
                }
        }
    }

    suspend fun deletePreset(presetId: String) {
        val user = currentUser ?: throw IllegalStateException("Пользователь не авторизован")
        val dbInstance = db ?: throw IllegalStateException("Firebase Firestore недоступен")

        suspendCoroutine<Unit> { continuation ->
            dbInstance.collection("users")
                .document(user.uid)
                .collection("presets")
                .document(presetId)
                .delete()
                .addOnSuccessListener {
                    continuation.resume(Unit)
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        }
    }
}
