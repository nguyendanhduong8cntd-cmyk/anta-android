package vn.anta.booking

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: a local backend with OTP_DEV_PUSH pushes the verification code to the phone that asked for it.
 * Firebase is set up from local.properties (anta.firebaseAppId, anta.firebaseApiKey, anta.firebaseProjectId,
 * anta.firebaseSenderId); without them nothing here runs and codes are read with the development key as before.
 */
object DevelopmentPush {
    private const val TOKEN_TIMEOUT_MS = 5_000L
    private const val NOTIFICATIONS_REQUEST = 7301

    @Volatile
    private var ready = false

    fun install(app: Application) {
        val options = options() ?: return
        if (FirebaseApp.getApps(app).isEmpty()) FirebaseApp.initializeApp(app, options)
        CodeNotifications.createChannel(app)
        askForNotificationsOnce(app)
        ready = true
        // The first registration with Google can take longer than a code request waits; started now, it is done by then.
        FirebaseMessaging.getInstance().token.addOnFailureListener { Logger.w("Push registration failed", throwable = it) }
    }

    /** This phone's push token; null when push is not set up or Firebase does not answer in time. */
    suspend fun token(): String? {
        if (!ready) return null
        return try {
            withTimeoutOrNull(TOKEN_TIMEOUT_MS) { FirebaseMessaging.getInstance().token.await() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w("No push token: the code will not be pushed", throwable = error)
            null
        }
    }

    private fun options(): FirebaseOptions? {
        val settings = listOf(BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_SENDER_ID)
        if (settings.any(String::isEmpty)) return null
        return FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
    }

    /** Android 13 and later show nothing without the user's leave: asked once, when the first screen appears. */
    private fun askForNotificationsOnce(app: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                app.unregisterActivityLifecycleCallbacks(this)
                if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS_REQUEST)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/** Shows a verification code that a local backend pushed. Anything else arriving here is ignored. */
class DevelopmentCodeService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "otp") return
        val code = message.data["code"]?.takeIf(CODE::matches) ?: return
        // Never the code itself: this line only tells "the push arrived" apart from "the push never came".
        Logger.d("Pushed verification code received", "sentAt=${message.sentTime} priority=${message.priority}")
        CodeNotifications.show(this, code, resettingPassword = message.data["purpose"] == "reset-password")
    }

    // A token is asked for whenever a code is, so a new one needs no handling here.
    override fun onNewToken(token: String) = Unit

    private companion object {
        val CODE = Regex("^\\d{6}$")
    }
}

private object CodeNotifications {
    private const val CHANNEL = "development-codes"
    private const val NOTIFICATION_ID = 7302
    private const val CODE_LIFETIME_MS = 5 * 60 * 1000L

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Mã xác minh (bản thử)", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, code: String, resettingPassword: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.w("A pushed verification code arrived, but notifications are not allowed")
            return
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val use = if (resettingPassword) "đặt lại mật khẩu" else "đăng ký tài khoản"
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Mã xác minh Anta: $code")
            .setContentText("Dùng mã này để $use. Mã hết hạn sau 5 phút.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(CODE_LIFETIME_MS)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            // Not a CancellationException: Firebase giving up must not cancel the caller, it only means no token.
            task.isCanceled -> continuation.resumeWithException(IllegalStateException("Firebase cancelled the request"))
            else -> continuation.resume(task.result)
        }
    }
}
