package com.alobo.clone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import java.util.ArrayDeque

enum class Screen {
    ONBOARDING, HOME, MAP, EXPLORE, FEATURED, ACCOUNT,
    LOGIN, REGISTER, FORGOT_PASSWORD, SEARCH, ADVANCED_SEARCH,
    VENUE_DETAIL, EVENT_DETAIL, BOOKING_SCHEDULE, QR_SCANNER, TERMS
}

enum class Overlay { NONE, LOGIN_REQUIRED, LANGUAGE, BOOKING_CHOICE, DATE_PICKER, TERMS_TOC }

private class SemanticActionView(context: Context) : View(context) {
    var activate: (() -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setWillNotDraw(true)
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    // Physical gestures continue to the Canvas view. Accessibility ACTION_CLICK
    // reaches performClick(), so semantics do not create a second touch layer.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false

    override fun performClick(): Boolean {
        super.performClick()
        activate?.invoke()
        return true
    }
}

class MainActivity : Activity() {
    companion object {
        const val FEEDBACK_EMAIL = "support@your-domain.com"
        private const val REQUEST_LOCATION = 101
        private const val REQUEST_NOTIFICATIONS = 102
        private const val REQUEST_CAMERA = 103
    }

    private lateinit var root: FrameLayout
    lateinit var content: AloboView
        private set
    private val editors = mutableMapOf<String, EditText>()
    private val semanticViews = mutableListOf<View>()
    private val editorValues = mutableMapOf<String, String>()
    private val visiblePasswords = mutableSetOf<String>()
    private val navigationStack = ArrayDeque<Screen>()
    private val regularTypeface by lazy { Typeface.createFromAsset(assets, "fonts/averta_regular.otf") }
    private var permissionsStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        configureWindow()

        root = FrameLayout(this)
        content = AloboView(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        content.requestFocus()

        showScreen(
            if (getPreferences(MODE_PRIVATE).getBoolean("onboarding_done", false)) Screen.HOME else Screen.ONBOARDING,
            remember = false,
            resetScroll = true
        )
        scheduleSurfaceFrames()
        root.post { requestInitialPermissions() }
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBar()
        contentOrNull()?.postInvalidateOnAnimation()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::content.isInitialized && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            content.trimBitmapMemory(level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideNavigationBar(contentOrNull()?.usesLightStatusIcons() ?: false)
            contentOrNull()?.postInvalidateOnAnimation()
        }
    }

    private fun configureWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
        hideNavigationBar()
    }

    fun hideNavigationBar(lightStatus: Boolean = contentOrNull()?.usesLightStatusIcons() ?: false) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                setSystemBarsAppearance(
                    if (lightStatus) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    (if (lightStatus) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        }
    }

    private fun contentOrNull(): AloboView? = if (::content.isInitialized) content else null

    private fun scheduleSurfaceFrames() {
        longArrayOf(120L, 500L, 1_500L).forEach { delay ->
            root.postDelayed({
                content.requestLayout()
                content.postInvalidateOnAnimation()
            }, delay)
        }
    }

    fun showScreen(screen: Screen, remember: Boolean = true, resetScroll: Boolean = false) {
        if (remember && ::content.isInitialized && content.screen != screen) {
            navigationStack.addLast(content.screen)
        }
        clearEditors()
        clearSemanticViews()
        content.screen = screen
        content.overlay = Overlay.NONE
        if (resetScroll) content.resetScroll(screen)
        content.invalidate()
        Logger.screen(screen.name)
        hideNavigationBar(content.usesLightStatusIcons())
        root.post {
            installSemanticViews()
            installEditors(screen)
            // Ensure the first Canvas frame is submitted after the decor surface is attached.
            content.postInvalidateOnAnimation()
        }
    }

    fun finishOnboarding() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("onboarding_done", true).apply()
        navigationStack.clear()
        showScreen(Screen.HOME, remember = false, resetScroll = true)
    }

    fun showMainTab(screen: Screen) {
        navigationStack.clear()
        if (screen != Screen.HOME) navigationStack.addLast(Screen.HOME)
        showScreen(screen, remember = false, resetScroll = true)
    }

    fun goBack(fallback: Screen) {
        val target = if (navigationStack.isEmpty()) fallback else navigationStack.removeLast()
        showScreen(target, remember = false)
    }

    fun showOverlay(overlay: Overlay) {
        content.overlay = overlay
        content.invalidate()
        Logger.dialog(overlay.name)
        hideKeyboard()
        refreshSemantics()
    }

    fun dismissOverlay() {
        if (content.overlay != Overlay.NONE) Logger.click("overlay_dismiss", content.screen.name)
        content.overlay = Overlay.NONE
        content.invalidate()
        refreshSemantics()
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Logger.dialog("TOAST: $message")
    }

    fun openUri(uri: String) {
        Logger.d("EXTERNAL_INTENT", uri)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            .onFailure { toast("Không thể mở liên kết") }
    }

    fun sendFeedback() {
        Logger.click("feedback", content.screen.name)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL"))
            .putExtra(Intent.EXTRA_SUBJECT, "Alobo Feedback")
        runCatching { startActivity(intent) }.onFailure { toast("Không tìm thấy ứng dụng email") }
    }

    fun requestCameraAndShowScanner() {
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScreen(Screen.QR_SCANNER)
        } else {
            Logger.dialog("SYSTEM_PERMISSION_CAMERA")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    private fun requestInitialPermissions() {
        if (permissionsStarted || getPreferences(MODE_PRIVATE).getBoolean("permissions_asked", false)) return
        permissionsStarted = true
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Logger.dialog("SYSTEM_PERMISSION_LOCATION")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
        } else {
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Logger.dialog("SYSTEM_PERMISSION_NOTIFICATIONS")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            markPermissionsAsked()
        }
    }

    private fun markPermissionsAsked() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("permissions_asked", true).apply()
        hideNavigationBar(content.usesLightStatusIcons())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.d("PERMISSION_RESULT", "request=$requestCode results=${grantResults.joinToString()}")
        when (requestCode) {
            REQUEST_LOCATION -> requestNotificationPermission()
            REQUEST_NOTIFICATIONS -> markPermissionsAsked()
            REQUEST_CAMERA -> showScreen(Screen.QR_SCANNER)
        }
    }

    private fun installEditors(screen: Screen) {
        when (screen) {
            Screen.LOGIN -> {
                if (content.loginByEmail) {
                    addEditor("login_email", 120, 610, 960, 735, "Nhập email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                } else {
                    addEditor("login_phone", 340, 610, 960, 735, "Nhập số điện thoại", InputType.TYPE_CLASS_PHONE)
                }
                addEditor("login_password", 120, 880, 850, 1005, "Nhập mật khẩu (*)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            }
            Screen.REGISTER -> {
                addEditor("register_phone", 340, 460, 960, 585, "Nhập số điện thoại", InputType.TYPE_CLASS_PHONE)
                addEditor("register_email", 120, 750, 850, 875, "Nhập email của bạn", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                addEditor("register_name", 120, 1050, 850, 1175, "Nhập họ và tên", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                addEditor("register_password", 120, 1350, 850, 1475, "Nhập mật khẩu (*)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                addEditor("register_confirm", 120, 1650, 850, 1775, "Nhập lại mật khẩu", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            }
            Screen.FORGOT_PASSWORD -> {
                if (content.forgotByPhone) {
                    addEditor(
                        "forgot_phone", 365, 1080, 960, 1205,
                        "Nhập số điện thoại",
                        InputType.TYPE_CLASS_PHONE
                    )
                } else {
                    addEditor(
                        "forgot_email", 120, 1080, 850, 1205,
                        "Nhập email đã đăng ký",
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    )
                }
            }
            Screen.SEARCH -> addEditor("search", 130, 250, 990, 365, "Nhập tên sân, địa chỉ", InputType.TYPE_CLASS_TEXT)
            else -> Unit
        }
    }

    fun refreshEditors() {
        clearEditors()
        refreshSemantics()
        root.post { installEditors(content.screen) }
    }

    fun refreshSemantics() {
        clearSemanticViews()
        root.post { installSemanticViews() }
    }

    private fun installSemanticViews() {
        val scale = root.width / 1080f
        content.semanticTargets().forEach { target ->
            val semanticView = SemanticActionView(this).apply {
                activate = { content.activateSemantic(target.id) }
                contentDescription = target.label
                isSelected = target.selected
                tag = target.key
            }
            val bounds = target.bounds
            val params = FrameLayout.LayoutParams(
                (bounds.width() * scale).toInt().coerceAtLeast(1),
                (bounds.height() * scale).toInt().coerceAtLeast(1)
            ).apply {
                leftMargin = (bounds.left * scale).toInt()
                topMargin = (bounds.top * scale).toInt()
            }
            semanticViews += semanticView
            root.addView(semanticView, params)
        }
    }

    private fun clearSemanticViews() {
        semanticViews.forEach(root::removeView)
        semanticViews.clear()
    }

    private fun addEditor(id: String, left: Int, top: Int, right: Int, bottom: Int, hint: String, type: Int) {
        val scale = root.width / 1080f
        val editor = EditText(this).apply {
            this.hint = hint
            inputType = type
            typeface = regularTypeface
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 34f * scale)
            includeFontPadding = false
            setTextColor(Color.rgb(60, 60, 64))
            setHintTextColor(Color.rgb(155, 155, 160))
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * scale).toInt(), 0, (8 * scale).toInt(), 0)
            setSingleLine(true)
            contentDescription = hint
            imeOptions = when (id) {
                "search" -> EditorInfo.IME_ACTION_SEARCH
                "login_password", "register_confirm", "forgot_phone", "forgot_email" -> EditorInfo.IME_ACTION_DONE
                else -> EditorInfo.IME_ACTION_NEXT
            }
            if (Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                setAutofillHints(
                    when {
                        id.contains("email") -> View.AUTOFILL_HINT_EMAIL_ADDRESS
                        id.contains("phone") -> View.AUTOFILL_HINT_PHONE
                        id.contains("name") -> View.AUTOFILL_HINT_NAME
                        id.contains("password") || id.contains("confirm") -> View.AUTOFILL_HINT_PASSWORD
                        else -> null
                    }
                )
            }
            setText(editorValues[id].orEmpty())
            setSelection(text.length)
            if (id.contains("password") || id.contains("confirm")) {
                transformationMethod = if (id in visiblePasswords) null else PasswordTransformationMethod.getInstance()
                setSelection(text.length)
            }
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) Logger.click("input_$id", content.screen.name) }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
                    Logger.click("ime_$id", content.screen.name)
                    hideKeyboard()
                    true
                } else false
            }
        }
        val params = FrameLayout.LayoutParams(((right - left) * scale).toInt(), ((bottom - top) * scale).toInt()).apply {
            leftMargin = (left * scale).toInt()
            topMargin = (top * scale).toInt()
        }
        editors[id] = editor
        root.addView(editor, params)
    }

    fun value(id: String): String = editors[id]?.text?.toString().orEmpty().trim()

    fun clearEditor(id: String) {
        editorValues[id] = ""
        editors[id]?.setText("")
    }

    fun togglePassword(id: String) {
        val editor = editors[id] ?: return
        if (!visiblePasswords.add(id)) visiblePasswords.remove(id)
        editor.transformationMethod = if (id in visiblePasswords) null else PasswordTransformationMethod.getInstance()
        editor.setSelection(editor.text?.length ?: 0)
    }

    private fun clearEditors() {
        editors.forEach { (id, editor) ->
            editorValues[id] = editor.text?.toString().orEmpty()
            root.removeView(editor)
        }
        editors.clear()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(root.windowToken, 0)
        content.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (content.overlay != Overlay.NONE) {
            dismissOverlay()
            return
        }
        Logger.click("system_back", content.screen.name)
        when (content.screen) {
            Screen.HOME, Screen.ONBOARDING -> super.onBackPressed()
            Screen.MAP, Screen.EXPLORE, Screen.FEATURED, Screen.ACCOUNT -> goBack(Screen.HOME)
            Screen.LOGIN, Screen.REGISTER -> goBack(Screen.ACCOUNT)
            Screen.FORGOT_PASSWORD -> goBack(Screen.LOGIN)
            Screen.SEARCH -> goBack(Screen.HOME)
            Screen.ADVANCED_SEARCH -> goBack(Screen.SEARCH)
            Screen.VENUE_DETAIL -> goBack(Screen.SEARCH)
            Screen.EVENT_DETAIL -> goBack(Screen.EXPLORE)
            Screen.BOOKING_SCHEDULE -> goBack(Screen.VENUE_DETAIL)
            Screen.QR_SCANNER -> goBack(Screen.HOME)
            Screen.TERMS -> goBack(Screen.ACCOUNT)
        }
    }
}
