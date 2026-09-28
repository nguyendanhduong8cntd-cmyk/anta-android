package com.alobo.clone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Shader
import android.os.Bundle
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AloboView(context: Context) : View(context) {
    private val activity get() = context as MainActivity
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapCache = object : LruCache<Int, Bitmap>(64 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }
    private val regular = Typeface.createFromAsset(context.assets, "fonts/averta_regular.otf")
    private val semibold = Typeface.createFromAsset(context.assets, "fonts/averta_semibold.otf")
    private val bold = Typeface.createFromAsset(context.assets, "fonts/averta_bold.otf")

    var screen: Screen = Screen.ONBOARDING
    var overlay: Overlay = Overlay.NONE
    var onboardingPage = 0
    var loginByEmail = false
    var forgotByPhone = false
    var venueTab = 0
    var exploreFilter = 0
    var selectedSport = 0
    private var downX = 0f
    private var downY = 0f
    private var dragStartOffset = 0f
    private var isDragging = false
    private var pressedX = 0f
    private var pressedY = 0f
    private var isPressedTarget = false
    private var accessibilityFocusedId = View.NO_ID
    private var hoveredVirtualId = View.NO_ID
    private val scrollOffsets = mutableMapOf<Screen, Float>()
    private var lastDrawnScreen: Screen? = null

    private val green = Color.rgb(0, 125, 65)
    private val darkGreen = Color.rgb(0, 78, 42)
    private val brightGreen = Color.rgb(0, 194, 49)
    private val mint = Color.rgb(239, 255, 248)
    private val gray = Color.rgb(98, 98, 103)
    private val lightGray = Color.rgb(244, 245, 246)
    private val gold = Color.rgb(238, 181, 35)
    private val teal = Color.rgb(31, 198, 150)

    data class SemanticTarget(
        val id: Int,
        val key: String,
        val label: String,
        val bounds: RectF,
        val selected: Boolean = false
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        // Native transparent semantic siblings in MainActivity expose each action.
        // Hide this drawing surface itself to avoid duplicate TalkBack nodes.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun viewportHeight(): Float = if (width > 0) height * 1080f / width else 2400f

    fun resetScroll(target: Screen) {
        scrollOffsets[target] = 0f
        if (screen == target) invalidate()
    }

    fun trimBitmapMemory(aggressive: Boolean) {
        if (aggressive) bitmapCache.evictAll() else bitmapCache.trimToSize(32 * 1024)
    }

    private fun bitmap(resId: Int): Bitmap = bitmapCache.get(resId)
        ?: BitmapFactory.decodeResource(resources, resId).also { bitmapCache.put(resId, it) }

    fun usesLightStatusIcons(): Boolean = when (screen) {
        Screen.LOGIN, Screen.REGISTER, Screen.FORGOT_PASSWORD, Screen.QR_SCANNER,
        Screen.BOOKING_SCHEDULE, Screen.FEATURED -> true
        else -> false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val logThisFrame = lastDrawnScreen != screen
        if (logThisFrame) Logger.d("SCREEN_DRAW_BEGIN", "screen=${screen.name} size=${width}x$height")
        val scale = width / 1080f
        val viewportHeight = viewportHeight()
        canvas.save()
        // The source app renders from a fixed-width Flutter viewport. Scaling X/Y
        // independently distorted circles, icons and type on 18:9/19.5:9 phones.
        canvas.scale(scale, scale)
        val scroll = scrollOffset()
        canvas.save()
        canvas.translate(0f, if (screen == Screen.MAP) 0f else -scroll)
        when (screen) {
            Screen.ONBOARDING -> drawOnboarding(canvas)
            Screen.HOME -> drawHome(canvas)
            Screen.MAP -> drawMap(canvas)
            Screen.EXPLORE -> drawExplore(canvas)
            Screen.FEATURED -> drawFeatured(canvas)
            Screen.ACCOUNT -> drawAccount(canvas)
            Screen.LOGIN -> drawLogin(canvas)
            Screen.REGISTER -> drawRegister(canvas)
            Screen.FORGOT_PASSWORD -> drawForgotPassword(canvas)
            Screen.SEARCH -> drawSearch(canvas)
            Screen.ADVANCED_SEARCH -> drawAdvancedSearch(canvas)
            Screen.VENUE_DETAIL -> drawVenueDetail(canvas)
            Screen.EVENT_DETAIL -> drawEventDetail(canvas)
            Screen.BOOKING_SCHEDULE -> drawBookingSchedule(canvas)
            Screen.QR_SCANNER -> drawQrScanner(canvas)
            Screen.TERMS -> drawTerms(canvas)
        }
        canvas.restore()
        mainNavIndex()?.let {
            canvas.save()
            canvas.translate(0f, viewportHeight - 2400f)
            drawBottomNav(canvas, it)
            canvas.restore()
        }
        if (overlay != Overlay.NONE) drawOverlay(canvas)
        if (isPressedTarget) drawTouchFeedback(canvas, pressedX, pressedY)
        canvas.restore()
        if (logThisFrame) {
            Logger.d("SCREEN_DRAW_END", "screen=${screen.name} size=${width}x$height")
            lastDrawnScreen = screen
        }
    }

    private fun mainNavIndex(): Int? = when (screen) {
        Screen.HOME -> 0
        Screen.MAP -> 1
        Screen.EXPLORE -> 2
        Screen.FEATURED -> 3
        Screen.ACCOUNT -> 4
        else -> null
    }

    private fun scrollMax(): Float = when (screen) {
        Screen.HOME -> 560f
        Screen.EXPLORE -> if (exploreFilter == 0) 0f else 220f
        else -> 0f
    }

    private fun scrollOffset(): Float = scrollOffsets[screen]?.coerceIn(0f, scrollMax()) ?: 0f

    private fun background(canvas: Canvas, color: Int) = canvas.drawColor(color)

    private fun fill(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 0f) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, paint)
    }

    private fun stroke(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 0f, width: Float = 2f) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, paint)
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 2f) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    private fun text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        weight: Typeface = regular,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        paint.textSize = size
        paint.typeface = weight
        paint.textAlign = align
        canvas.drawText(value, x, y, paint)
    }

    private fun underlinedText(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        weight: Typeface = regular,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        paint.textSize = size
        paint.typeface = weight
        paint.textAlign = align
        paint.isUnderlineText = true
        canvas.drawText(value, x, y, paint)
    }

    private fun wrapped(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        size: Float,
        color: Int,
        weight: Typeface = regular,
        lineHeight: Float = size * 1.25f,
        maxLines: Int = 10,
        align: Paint.Align = Paint.Align.LEFT
    ): Float {
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = size
        paint.typeface = weight
        val words = value.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val proposed = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(proposed) <= maxWidth || current.isEmpty()) current = proposed
            else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        lines.take(maxLines).forEachIndexed { index, row ->
            text(canvas, row, x, y + index * lineHeight, size, color, weight, align)
        }
        return y + min(lines.size, maxLines) * lineHeight
    }

    private fun image(
        canvas: Canvas,
        resId: Int,
        rect: RectF,
        crop: Boolean = true,
        radius: Float = 0f,
        tint: Int? = null,
        alpha: Int = 255
    ) {
        val bitmap = bitmap(resId)
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.alpha = alpha
            if (tint != null) colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        canvas.save()
        if (radius > 0) {
            val path = Path()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(path)
        }
        if (crop) {
            val scale = max(rect.width() / bitmap.width, rect.height() / bitmap.height)
            val sourceWidth = rect.width() / scale
            val sourceHeight = rect.height() / scale
            val left = ((bitmap.width - sourceWidth) / 2f).toInt().coerceAtLeast(0)
            val top = ((bitmap.height - sourceHeight) / 2f).toInt().coerceAtLeast(0)
            val src = Rect(left, top, min(bitmap.width, (left + sourceWidth).toInt()), min(bitmap.height, (top + sourceHeight).toInt()))
            canvas.drawBitmap(bitmap, src, rect, imagePaint)
        } else {
            canvas.drawBitmap(bitmap, null, rect, imagePaint)
        }
        canvas.restore()
    }

    private fun imageRegion(canvas: Canvas, resId: Int, source: Rect, destination: RectF) {
        val bitmap = bitmap(resId)
        val safeSource = Rect(
            source.left.coerceIn(0, bitmap.width),
            source.top.coerceIn(0, bitmap.height),
            source.right.coerceIn(0, bitmap.width),
            source.bottom.coerceIn(0, bitmap.height)
        )
        canvas.drawBitmap(bitmap, safeSource, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun vector(canvas: Canvas, resId: Int, rect: RectF, tint: Int? = null) {
        @Suppress("DEPRECATION")
        val drawable = resources.getDrawable(resId, context.theme).mutate()
        if (tint != null) drawable.setTint(tint)
        drawable.bounds = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        drawable.draw(canvas)
    }

    private fun drawTopBack(canvas: Canvas, title: String, greenHeader: Boolean = false) {
        if (greenHeader) fill(canvas, 0f, 0f, 1080f, 260f, green)
        image(canvas, R.drawable.icon_back, RectF(45f, 145f, 80f, 195f), crop = false, tint = if (greenHeader) Color.WHITE else green)
        text(canvas, title, 540f, 205f, 43f, if (greenHeader) Color.WHITE else green, bold, Paint.Align.CENTER)
    }

    private fun drawOnboarding(canvas: Canvas) {
        background(canvas, Color.rgb(250, 250, 250))
        text(canvas, "Bỏ Qua", 988f, 205f, 34f, green, semibold, Paint.Align.RIGHT)
        image(canvas, R.drawable.icon_arrow_right, RectF(994f, 170f, 1028f, 210f), crop = false, tint = green)
        val pictures = intArrayOf(R.drawable.onboarding_1, R.drawable.onboarding_2, R.drawable.onboarding_3, R.drawable.onboarding_4)
        image(canvas, pictures[onboardingPage], RectF(190f, 610f, 890f, 1230f), crop = false, radius = 40f)
        val titles = arrayOf(
            "Khám phá thế giới thể thao dễ dàng hơn bao giờ hết!",
            "Huấn luyện cùng chuyên gia",
            "Mọi môn thể thao trong tầm tay",
            "Bắt đầu ngay!"
        )
        val subtitles = arrayOf(
            "Đặt sân mọi lúc, mọi nơi. Kết nối với hàng ngàn môn thể thao và vận động viên khắp mọi nơi",
            "Kết nối cộng đồng, tìm bạn chơi và nâng cao sức khỏe mỗi ngày",
            "Tìm sân, sự kiện và lớp học phù hợp với bạn chỉ trong vài thao tác",
            "Hãy cùng chúng tôi bắt đầu hành trình thể thao của bạn ngay hôm nay"
        )
        wrapped(canvas, titles[onboardingPage], 540f, 1450f, 930f, 39f, Color.rgb(16, 24, 40), bold, 50f, 3, Paint.Align.CENTER)
        wrapped(canvas, subtitles[onboardingPage], 540f, 1600f, 950f, 27f, Color.rgb(90, 99, 113), regular, 34f, 4, Paint.Align.CENTER)
        for (i in 0..3) {
            if (i == onboardingPage) fill(canvas, 445f + i * 42f, 2100f, 520f + i * 42f, 2120f, green, 12f)
            else fill(canvas, 462f + i * 42f, 2100f, 482f + i * 42f, 2120f, Color.rgb(155, 155, 155), 10f)
        }
        line(canvas, 0f, 2160f, 1080f, 2160f, Color.rgb(225, 227, 230), 2f)
        fill(canvas, 38f, 2200f, 1042f, 2305f, green, 25f)
        text(canvas, "Tiếp tục", 540f, 2272f, 34f, Color.WHITE, semibold, Paint.Align.CENTER)
    }

    private fun drawHome(canvas: Canvas) {
        background(canvas, Color.WHITE)
        image(canvas, R.drawable.home_header, RectF(0f, 0f, 1080f, 410f), crop = true)
        fill(canvas, 0f, 0f, 1080f, 410f, Color.argb(90, 0, 125, 65))
        image(canvas, R.drawable.alobo_logo, RectF(25f, 115f, 170f, 260f), crop = false)
        text(canvas, "Thứ hai, 21/09/2026", 202f, 190f, 31f, Color.WHITE)
        fill(canvas, 202f, 225f, 492f, 305f, Color.WHITE, 14f)
        text(canvas, "Đăng nhập", 347f, 278f, 27f, darkGreen, semibold, Paint.Align.CENTER)
        stroke(canvas, 515f, 225f, 803f, 305f, Color.WHITE, 14f, 4f)
        text(canvas, "Đăng kí", 659f, 278f, 27f, Color.WHITE, semibold, Paint.Align.CENTER)
        fill(canvas, 970f, 130f, 1030f, 190f, Color.rgb(239, 42, 77), 30f)
        image(canvas, R.drawable.icon_star, RectF(985f, 145f, 1015f, 175f), crop = false, tint = Color.YELLOW)

        fill(canvas, 24f, 330f, 918f, 458f, Color.WHITE, 24f)
        image(canvas, R.drawable.alobo_mark, RectF(52f, 355f, 105f, 408f), crop = false)
        text(canvas, "Tìm kiếm", 125f, 405f, 28f, Color.rgb(185, 185, 190))
        vector(canvas, R.drawable.ic_qr_scan, RectF(758f, 362f, 810f, 414f), green)
        vector(canvas, R.drawable.ic_search, RectF(842f, 358f, 895f, 411f), green)
        fill(canvas, 940f, 330f, 1055f, 458f, Color.WHITE, 24f)
        vector(canvas, R.drawable.ic_heart_outline, RectF(970f, 365f, 1028f, 423f), green)

        val chips = arrayOf("Pickleball gần tôi", "Cầu lông gần tôi", "Xé vé gần tôi")
        var left = 24f
        chips.forEachIndexed { index, chip ->
            val widths = floatArrayOf(345f, 336f, 280f)
            fill(canvas, left, 486f, left + widths[index], 585f, Color.rgb(225, 234, 230), 12f)
            text(canvas, chip, left + widths[index] / 2f, 550f, 25f, darkGreen, regular, Paint.Align.CENTER)
            left += widths[index] + 14f
        }

        val sports = arrayOf("Pickleball", "Cầu lông", "Bóng đá", "Bóng rổ", "Quần vợt")
        val sportImages = intArrayOf(R.drawable.pickleball_cover, R.drawable.onboarding_1, R.drawable.football_cover, R.drawable.onboarding_3, R.drawable.tennis_cover)
        for (i in sports.indices) {
            val x = 55f + i * 195f
            fill(canvas, x, 612f, x + 104f, 716f, Color.WHITE, 12f)
            image(canvas, sportImages[i], RectF(x + 2f, 614f, x + 102f, 714f), crop = true, radius = 12f)
            text(canvas, sports[i], x + 52f, 760f, 24f, darkGreen, regular, Paint.Align.CENTER)
        }
        drawVenueCard(canvas, 850f, "Pickleball cổng B Ga Hà Nội", "273.5m")
        drawVenueCard(canvas, 1380f, "Phúc Long Pickleball 460 Trần Quý Cáp", "288.4m")
        drawVenueCard(canvas, 1910f, "Pickleball Vera Diamond", "1.2km")
    }

    private fun drawVenueCard(canvas: Canvas, y: Float, name: String, distance: String) {
        image(canvas, R.drawable.pickleball_cover, RectF(24f, y, 1056f, y + 310f), crop = true, radius = 12f)
        fill(canvas, 42f, y + 18f, 270f, y + 70f, Color.rgb(0, 214, 117), 26f)
        fill(canvas, 42f, y + 18f, 96f, y + 72f, Color.WHITE, 27f)
        image(canvas, R.drawable.icon_star, RectF(53f, y + 29f, 85f, y + 61f), crop = false, tint = Color.rgb(155, 158, 160))
        text(canvas, "Đơn ngày", 102f, y + 55f, 24f, Color.WHITE)
        fill(canvas, 270f, y + 18f, 400f, y + 70f, Color.rgb(226, 105, 218), 26f)
        text(canvas, "Sự kiện", 335f, y + 55f, 22f, Color.WHITE, regular, Paint.Align.CENTER)
        fill(canvas, 850f, y + 15f, 930f, y + 95f, Color.WHITE, 40f)
        vector(canvas, R.drawable.ic_heart_outline, RectF(867f, y + 32f, 913f, y + 78f), darkGreen)
        fill(canvas, 950f, y + 15f, 1030f, y + 95f, Color.WHITE, 40f)
        image(canvas, R.drawable.icon_route, RectF(967f, y + 32f, 1013f, y + 78f), crop = false, tint = darkGreen)
        fill(canvas, 29f, y + 283f, 1061f, y + 483f, Color.argb(35, 0, 45, 24), 12f)
        fill(canvas, 24f, y + 275f, 1056f, y + 475f, Color.WHITE, 12f)
        image(canvas, R.drawable.alobo_logo, RectF(42f, y + 315f, 170f, y + 443f), crop = false)
        wrapped(canvas, name, 190f, y + 335f, 610f, 29f, darkGreen, bold, 34f, 2)
        text(canvas, "($distance)", 190f, y + 405f, 23f, Color.rgb(255, 95, 48))
        text(canvas, "1 P. Trần Quý Cáp, Hà Nội", 300f, y + 405f, 23f, Color.rgb(74, 74, 78))
        vector(canvas, R.drawable.ic_clock_filled, RectF(190f, y + 423f, 214f, y + 447f), Color.rgb(45, 45, 48))
        text(canvas, "05:00 - 24:00", 225f, y + 446f, 23f, darkGreen)
        fill(canvas, 842f, y + 355f, 1038f, y + 430f, gold, 12f)
        text(canvas, "ĐẶT LỊCH", 940f, y + 404f, 24f, Color.WHITE, bold, Paint.Align.CENTER)
    }

    private fun drawBottomNav(canvas: Canvas, active: Int) {
        val panel = Path().apply {
            moveTo(0f, 2186f)
            cubicTo(0f, 2158f, 18f, 2140f, 48f, 2140f)
            lineTo(426f, 2140f)
            cubicTo(455f, 2140f, 458f, 2064f, 540f, 2064f)
            cubicTo(622f, 2064f, 625f, 2140f, 654f, 2140f)
            lineTo(1032f, 2140f)
            cubicTo(1062f, 2140f, 1080f, 2158f, 1080f, 2186f)
            lineTo(1080f, 2400f)
            lineTo(0f, 2400f)
            close()
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(22, 0, 78, 42)
        canvas.save()
        canvas.translate(0f, 10f)
        canvas.drawPath(panel, paint)
        canvas.restore()
        paint.color = Color.WHITE
        canvas.drawPath(panel, paint)

        fill(canvas, 448f, 2058f, 632f, 2242f, Color.argb(52, 0, 211, 28), 92f)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        if (active == 2) {
            paint.shader = LinearGradient(463f, 2073f, 617f, 2227f, Color.rgb(0, 211, 28), Color.rgb(0, 109, 56), Shader.TileMode.CLAMP)
            canvas.drawOval(RectF(463f, 2073f, 617f, 2227f), paint)
        } else {
            paint.color = Color.WHITE
            canvas.drawOval(RectF(463f, 2073f, 617f, 2227f), paint)
            stroke(canvas, 465f, 2075f, 615f, 2225f, green, 75f, 4f)
        }
        val outlineIcons = intArrayOf(
            R.drawable.nav_home_outline,
            R.drawable.nav_map_outline,
            R.drawable.nav_explore_outline,
            R.drawable.nav_featured_outline,
            R.drawable.nav_account_outline
        )
        val activeIcons = intArrayOf(
            R.drawable.nav_home_active,
            R.drawable.nav_map_active,
            R.drawable.nav_explore_outline,
            R.drawable.nav_featured_active,
            R.drawable.nav_account_active
        )
        val labels = arrayOf("Trang chủ", "Bản đồ", "Khám phá", "Nổi bật", "Tài khoản")
        val centers = floatArrayOf(145f, 342f, 540f, 738f, 935f)
        for (i in centers.indices) {
            val iconY = if (i == 2) 2112f else 2183f
            val size = if (i == 2) 70f else 62f
            val icon = if (active == i) activeIcons[i] else outlineIcons[i]
            val tint = when {
                i == 2 && active == 2 -> Color.WHITE
                i == 2 -> green
                active == i -> brightGreen
                else -> Color.rgb(150, 150, 150)
            }
            val iconTint = if (active == i && i != 2) null else tint
            vector(canvas, icon, RectF(centers[i] - size / 2f, iconY, centers[i] + size / 2f, iconY + size), iconTint)
            text(canvas, labels[i], centers[i], 2310f, 27f, if (active == i) brightGreen else Color.rgb(150, 150, 150), if (active == i) bold else regular, Paint.Align.CENTER)
        }
    }

    private fun drawTouchFeedback(canvas: Canvas, x: Float, y: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(28, 0, 125, 65)
        canvas.drawCircle(x, y, 42f, paint)
    }

    private fun drawMap(canvas: Canvas) {
        background(canvas, Color.WHITE)
        fill(canvas, 0f, 0f, 1080f, 112f, Color.rgb(134, 211, 229))
        imageRegion(canvas, R.drawable.map_runtime_reference, Rect(0, 112, 1080, 2140), RectF(0f, 112f, 1080f, 2140f))
        return
        @Suppress("UNREACHABLE_CODE")
        canvas.save()
        canvas.clipRect(0f, 1050f, 1080f, 2140f)
        canvas.translate(0f, -scrollOffset())
        fill(canvas, 0f, 1050f, 1080f, 2550f, Color.WHITE, 34f)
        val venues = arrayOf(
            "MINH THẢO SPORT CENTER",
            "Sân Cầu lông Bá Học Badminton",
            "Trung tâm huấn luyện cầu lông Sao Việt",
            "VMH CS2 CẨM PHẢ",
            "VMH CS1 CẨM PHẢ",
            "CLB Cầu lông Khương Liễu"
        )
        val addresses = arrayOf(
            "(11639.5km) 92 KHU ĐÔ THỊ ASC MÓNG CÁI 1 - QUẢNG NINH",
            "(11687.7km) Số 06-07 NVB, KĐT Phú Lộc I+II, Lạng Sơn",
            "(11729.1km) số 122 đường vùng Đục, thành phố Cẩm Phả",
            "(11730.0km) Số 12, tổ 4, khu 8, Cẩm Thành, Cẩm Phả",
            "(11730.0km) Số 11, tổ 3, khu 8, Cẩm Thành, Cẩm Phả",
            "(11744.7km) Khu đô thị Ao Cá, phường Cao Thắng"
        )
        venues.forEachIndexed { i, name ->
            val y = 1050f + i * 210f
            val markerSource = if (i == 0) Rect(45, 1060, 115, 1145) else Rect(45, 1250, 115, 1340)
            imageRegion(canvas, R.drawable.map_runtime_reference, markerSource, RectF(52f, y + 34f, 120f, y + 119f))
            text(canvas, name, 172f, y + 54f, 31f, darkGreen)
            wrapped(canvas, addresses[i], 172f, y + 100f, 700f, 24f, Color.rgb(130, 130, 130), regular, 31f, 2)
            imageRegion(canvas, R.drawable.map_runtime_reference, Rect(940, 1080, 1025, 1160), RectF(950f, y + 45f, 1018f, y + 113f))
            line(canvas, 0f, y + 205f, 1080f, y + 205f, Color.rgb(230, 230, 230), 1f)
        }
        canvas.restore()
    }

    private fun drawExplore(canvas: Canvas) {
        background(canvas, Color.WHITE)
        val reference = when (exploreFilter) {
            1 -> R.drawable.explore_event_runtime_reference
            2 -> R.drawable.explore_membership_runtime_reference
            3 -> R.drawable.explore_course_runtime_reference
            else -> R.drawable.explore_runtime_reference
        }
        imageRegion(canvas, reference, Rect(0, 112, 1080, 2140), RectF(0f, 112f, 1080f, 2140f))
        return
        @Suppress("UNREACHABLE_CODE")
        text(canvas, "KHÁM PHÁ", 48f, 195f, 46f, brightGreen, bold)
        val filters = arrayOf("Tất cả", "Sự kiện", "Gói hội viên", "Khóa học")
        var x = 28f
        filters.forEachIndexed { index, label ->
            val w = floatArrayOf(160f, 240f, 320f, 240f)[index]
            if (index == exploreFilter) stroke(canvas, x, 240f, x + w, 320f, green, 24f, 3f) else stroke(canvas, x, 240f, x + w, 320f, Color.rgb(220, 220, 225), 24f, 3f)
            text(canvas, label, x + w / 2f, 293f, 26f, if (index == exploreFilter) green else Color.rgb(155, 155, 160), if (index == exploreFilter) semibold else regular, Paint.Align.CENTER)
            x += w + 18f
        }
        image(canvas, R.drawable.alobo_logo, RectF(30f, 370f, 165f, 505f), crop = false)
        text(canvas, "Renix Pickleball Club", 190f, 410f, 30f, Color.rgb(32, 32, 34), bold)
        text(canvas, "(11814.7km) Ô đất A.3/CCTP3 - đường Khai Sơn", 190f, 460f, 23f, Color.rgb(112, 112, 116))
        text(canvas, "13:19  -  21/09/2026", 190f, 505f, 22f, Color.rgb(112, 112, 116))
        text(canvas, "Sự kiện mới", 55f, 575f, 31f, Color.rgb(34, 34, 36))
        text(canvas, "#sukiensocial", 48f, 620f, 25f, green)
        fill(canvas, 28f, 650f, 1052f, 1500f, Color.rgb(238, 255, 247), 28f)
        fill(canvas, 48f, 670f, 1030f, 1465f, Color.WHITE, 24f)
        image(canvas, R.drawable.pickleball_cover, RectF(650f, 670f, 1030f, 1010f), crop = true, radius = 22f)
        fill(canvas, 75f, 705f, 330f, 760f, Color.rgb(53, 105, 238), 28f)
        text(canvas, "Còn 02:07:42", 202f, 742f, 23f, Color.WHITE, semibold, Paint.Align.CENTER)
        fill(canvas, 350f, 705f, 510f, 760f, Color.rgb(250, 230, 255), 28f)
        text(canvas, "Xé vé", 430f, 742f, 23f, Color.rgb(185, 49, 230), semibold, Paint.Align.CENTER)
        text(canvas, "17:00", 100f, 865f, 29f, Color.rgb(48, 99, 225), bold)
        text(canvas, "21:00", 100f, 945f, 29f, Color.rgb(48, 99, 225), bold)
        text(canvas, "Xé vé", 250f, 835f, 34f, Color.rgb(30, 30, 32), bold)
        text(canvas, "Sân 2 - 3", 250f, 955f, 27f, Color.rgb(35, 35, 37), semibold)
        text(canvas, "60.000 đ", 250f, 1040f, 36f, Color.rgb(239, 143, 0), bold)
        fill(canvas, 78f, 1090f, 1005f, 1160f, Color.rgb(255, 252, 234), 16f)
        text(canvas, "Vé đã bao gồm 1 chai nước", 110f, 1135f, 23f, Color.rgb(174, 87, 25))
        line(canvas, 75f, 1210f, 1005f, 1210f, Color.rgb(225, 225, 230), 3f)
        text(canvas, "6/16 người tham gia", 420f, 1300f, 24f, gray)
        fill(canvas, 75f, 1340f, 1005f, 1355f, Color.rgb(232, 232, 232), 8f)
        fill(canvas, 75f, 1340f, 430f, 1355f, green, 8f)
        fill(canvas, 760f, 1380f, 1005f, 1450f, Color.rgb(238, 255, 247), 16f)
        text(canvas, "Mua vé", 885f, 1426f, 26f, green, semibold, Paint.Align.CENTER)
        image(canvas, R.drawable.social_pickleball, RectF(28f, 1540f, 1052f, 1900f), crop = true, radius = 18f)
    }

    private fun drawFeatured(canvas: Canvas) {
        background(canvas, Color.WHITE)
        fill(canvas, 0f, 0f, 1080f, 112f, Color.rgb(0, 160, 85))
        imageRegion(canvas, R.drawable.featured_runtime_reference, Rect(0, 112, 1080, 2140), RectF(0f, 112f, 1080f, 2140f))
    }

    private fun drawPromo(canvas: Canvas, y: Float, res: Int, title: String, subtitle: String) {
        image(canvas, res, RectF(24f, y, 1056f, y + 490f), crop = true, radius = 18f)
        fill(canvas, 24f, y, 620f, y + 490f, Color.argb(175, 0, 65, 160), 18f)
        wrapped(canvas, title, 70f, y + 105f, 510f, 46f, Color.WHITE, bold, 54f, 3)
        wrapped(canvas, subtitle, 70f, y + 260f, 510f, 34f, Color.WHITE, bold, 43f, 3)
        fill(canvas, 770f, y + 365f, 1030f, y + 455f, gold, 16f)
        text(canvas, "ĐẶT LỊCH NGAY", 900f, y + 423f, 24f, Color.WHITE, semibold, Paint.Align.CENTER)
    }

    private fun drawAccount(canvas: Canvas) {
        background(canvas, mint)
        image(canvas, R.drawable.user_auth_login, RectF(0f, 0f, 1080f, 430f), crop = false)
        fill(canvas, 0f, 320f, 1080f, 2160f, mint, 62f)
        image(canvas, R.drawable.alobo_logo, RectF(48f, 455f, 220f, 627f), crop = false)
        wrapped(canvas, "ALOBO - Đặt lịch online sân thể thao", 260f, 430f, 675f, 42f, Color.rgb(20, 20, 22), bold, 53f, 2)
        text(canvas, "Tạo tài khoản để nhận nhiều ưu đãi hơn", 260f, 560f, 27f, gold)
        fill(canvas, 260f, 595f, 548f, 695f, Color.rgb(0, 161, 81), 14f)
        text(canvas, "Đăng nhập", 404f, 660f, 29f, Color.WHITE, semibold, Paint.Align.CENTER)
        stroke(canvas, 596f, 595f, 884f, 695f, Color.rgb(30, 30, 30), 14f, 3f)
        text(canvas, "Đăng kí", 740f, 660f, 29f, darkGreen, semibold, Paint.Align.CENTER)
        text(canvas, "Hoạt động", 48f, 770f, 31f, green, bold)
        accountRow(canvas, 48f, 800f, "Danh sách lịch đã đặt", R.drawable.ic_account_calendar)
        text(canvas, "Hệ thống", 48f, 995f, 31f, green, bold)
        fill(canvas, 48f, 1025f, 1032f, 1517f, Color.WHITE, 24f)
        accountRowContent(canvas, 48f, 1025f, "Thông tin phiên bản: 2.10.1", R.drawable.ic_info)
        line(canvas, 48f, 1148f, 1032f, 1148f, Color.rgb(224, 226, 228), 1f)
        accountRowContent(canvas, 48f, 1148f, "Điều khoản và chính sách", R.drawable.ic_policy)
        line(canvas, 48f, 1271f, 1032f, 1271f, Color.rgb(224, 226, 228), 1f)
        accountRowContent(canvas, 48f, 1271f, "Ứng dụng có gì mới", R.drawable.ic_profile_new_badge, showNew = true)
        line(canvas, 48f, 1394f, 1032f, 1394f, Color.rgb(224, 226, 228), 1f)
        accountRowContent(canvas, 48f, 1394f, "Ngôn ngữ - Tiếng Việt", R.drawable.ic_translate_account)
    }

    private fun accountRow(canvas: Canvas, x: Float, y: Float, label: String, iconRes: Int) {
        fill(canvas, x, y, 1032f, y + 118f, Color.WHITE, 20f)
        accountRowContent(canvas, x, y, label, iconRes)
    }

    private fun accountRowContent(canvas: Canvas, x: Float, y: Float, label: String, iconRes: Int, showNew: Boolean = false) {
        vector(canvas, iconRes, RectF(x + 28f, y + 28f, x + 88f, y + 88f))
        if (showNew) text(canvas, "NEW", x + 58f, y + 65f, 12f, green, bold, Paint.Align.CENTER)
        text(canvas, label, x + 120f, y + 76f, 31f, gray)
        vector(canvas, R.drawable.ic_chevron_right, RectF(965f, y + 34f, 1005f, y + 84f), gray)
    }

    private fun drawAuthBackground(canvas: Canvas, title: String) {
        background(canvas, green)
        image(canvas, R.drawable.user_auth_login, RectF(0f, 0f, 1080f, 2400f), crop = true)
        image(canvas, R.drawable.icon_back, RectF(52f, 145f, 84f, 195f), crop = false, tint = Color.WHITE)
        text(canvas, title, 540f, 205f, 43f, Color.WHITE, bold, Paint.Align.CENTER)
    }

    private fun field(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        fill(canvas, l, t, r, b, Color.WHITE, 12f)
        stroke(canvas, l, t, r, b, Color.rgb(210, 210, 213), 12f, 2f)
    }

    private fun drawLogin(canvas: Canvas) {
        drawAuthBackground(canvas, "Đăng nhập")
        fill(canvas, 48f, 260f, 1032f, 1600f, Color.WHITE, 12f)
        val inactiveLeft = if (loginByEmail) 48f else 540f
        val inactiveRight = if (loginByEmail) 540f else 1032f
        fill(canvas, inactiveLeft + 12f, 274f, inactiveRight, 430f, Color.argb(42, 0, 0, 0), 18f)
        fill(canvas, inactiveLeft, 260f, inactiveRight, 408f, Color.rgb(247, 247, 247), 12f)
        fill(canvas, if (loginByEmail) 540f else 48f, 260f, if (loginByEmail) 1032f else 540f, 408f, Color.WHITE, 12f)
        text(canvas, "Số điện thoại", 294f, 345f, 34f, if (!loginByEmail) darkGreen else Color.rgb(160, 160, 160), bold, Paint.Align.CENTER)
        text(canvas, "Email", 786f, 345f, 34f, if (loginByEmail) darkGreen else Color.rgb(160, 160, 160), bold, Paint.Align.CENTER)
        text(canvas, if (loginByEmail) "Email của bạn?" else "Số điện thoại của bạn?", 96f, 535f, 36f, darkGreen, bold)
        field(canvas, 96f, 575f, 984f, 735f)
        if (!loginByEmail) {
            vector(canvas, R.drawable.ic_vietnam_flag, RectF(127f, 618f, 177f, 668f))
            text(canvas, "+ 84", 198f, 657f, 27f, darkGreen)
            vector(canvas, R.drawable.ic_down_green, RectF(292f, 638f, 322f, 666f), Color.rgb(150, 150, 155))
        }
        text(canvas, "Mật khẩu (*)", 96f, 835f, 36f, darkGreen, bold)
        field(canvas, 96f, 875f, 984f, 1015f)
        vector(canvas, R.drawable.ic_eye_off, RectF(905f, 915f, 957f, 967f), green)
        fill(canvas, 96f, 1090f, 984f, 1215f, green, 12f)
        text(canvas, "ĐĂNG NHẬP", 540f, 1170f, 31f, Color.WHITE, bold, Paint.Align.CENTER)
        stroke(canvas, 96f, 1260f, 984f, 1385f, Color.rgb(130, 192, 160), 12f, 2f)
        vector(canvas, R.drawable.ic_face_id, RectF(145f, 1287f, 213f, 1357f), green)
        text(canvas, "Đăng nhập với sinh trắc học", 570f, 1338f, 28f, darkGreen, semibold, Paint.Align.CENTER)
        text(canvas, "Bạn quên mật khẩu?", 252f, 1490f, 27f, darkGreen)
        underlinedText(canvas, "Quên mật khẩu", 590f, 1490f, 27f, darkGreen, bold)
        text(canvas, "Bạn chưa có tài khoản?", 272f, 1710f, 28f, Color.WHITE)
        text(canvas, "Đăng ký", 675f, 1710f, 28f, Color.WHITE, bold)
        fill(canvas, 86f, 1790f, 994f, 1908f, Color.WHITE, 12f)
        image(canvas, R.drawable.icon_google, RectF(285f, 1822f, 340f, 1877f), crop = false)
        text(canvas, "Đăng nhập với Google", 570f, 1868f, 34f, darkGreen, regular, Paint.Align.CENTER)
        image(canvas, R.drawable.owner_alert_bg, RectF(48f, 2000f, 1032f, 2310f), crop = false)
        underlinedText(canvas, "Nếu bạn là CHỦ SÂN hoặc NHÂN VIÊN, Bấm vào", 540f, 2103f, 28f, gold, regular, Paint.Align.CENTER)
        underlinedText(canvas, "đây để tải ứng dụng ALOBO - Quản lý sân thể", 540f, 2180f, 28f, gold, regular, Paint.Align.CENTER)
        underlinedText(canvas, "thao!", 540f, 2257f, 28f, gold, regular, Paint.Align.CENTER)
    }

    private fun drawRegister(canvas: Canvas) {
        drawAuthBackground(canvas, "Đăng ký")
        fill(canvas, 48f, 305f, 1032f, 2310f, Color.WHITE, 22f)
        text(canvas, "Số điện thoại của bạn?", 96f, 420f, 32f, darkGreen, bold)
        field(canvas, 96f, 450f, 984f, 595f)
        vector(canvas, R.drawable.ic_vietnam_flag, RectF(127f, 492f, 177f, 542f))
        text(canvas, "+ 84", 198f, 532f, 25f, darkGreen)
        vector(canvas, R.drawable.ic_down_green, RectF(292f, 511f, 322f, 539f), Color.rgb(150, 150, 155))
        val labels = arrayOf("Email của bạn?", "Tên đầy đủ (*)", "Mật khẩu (*)", "Nhập mật khẩu")
        val ys = floatArrayOf(705f, 1005f, 1305f, 1605f)
        ys.forEachIndexed { index, y ->
            text(canvas, labels[index], 96f, y, 31f, darkGreen, bold)
            field(canvas, 96f, y + 30f, 984f, y + 175f)
            when (index) {
                0, 1 -> vector(canvas, R.drawable.ic_clear_circle, RectF(902f, y + 73f, 954f, y + 125f), green)
                2, 3 -> vector(canvas, R.drawable.ic_eye_off, RectF(902f, y + 73f, 954f, y + 125f), green)
            }
        }
        fill(canvas, 96f, 1905f, 984f, 2030f, green, 12f)
        text(canvas, "ĐĂNG KÝ", 540f, 1985f, 31f, Color.WHITE, bold, Paint.Align.CENTER)
        text(canvas, "Bạn đã có tài khoản?", 280f, 2190f, 27f, Color.rgb(35, 35, 38))
        text(canvas, "Đăng nhập", 650f, 2190f, 27f, green, bold)
    }

    private fun drawForgotPassword(canvas: Canvas) {
        image(canvas, R.drawable.user_auth_login, RectF(0f, 0f, 1080f, 2400f), crop = true)
        val reference = if (forgotByPhone) {
            R.drawable.forgot_phone_runtime_reference
        } else {
            R.drawable.forgot_email_runtime_reference
        }
        imageRegion(canvas, reference, Rect(0, 112, 1080, 2340), RectF(0f, 112f, 1080f, 2340f))
        if (forgotByPhone) {
            fill(canvas, 365f, 1102f, 960f, 1198f, Color.WHITE)
        } else {
            fill(canvas, 120f, 1102f, 850f, 1198f, Color.WHITE)
        }
        return
        @Suppress("UNREACHABLE_CODE")
        drawAuthBackground(canvas, "Quên mật khẩu")
        fill(canvas, 48f, 280f, 1032f, 2050f, Color.WHITE, 14f)
        wrapped(canvas, "Nhập email hoặc số điện thoại đã đăng ký để tìm kiếm và lấy lại mật khẩu.", 96f, 610f, 888f, 27f, gray, regular, 38f, 3)
        text(canvas, "Tìm kiếm tài khoản theo", 96f, 785f, 30f, darkGreen, bold)
        stroke(canvas, 96f, 820f, 525f, 935f, if (!forgotByPhone) green else Color.rgb(220, 220, 220), 12f, 3f)
        stroke(canvas, 555f, 820f, 984f, 935f, if (forgotByPhone) green else Color.rgb(220, 220, 220), 12f, 3f)
        text(canvas, "Email", 310f, 890f, 28f, if (!forgotByPhone) green else gray, semibold, Paint.Align.CENTER)
        text(canvas, "Số điện thoại", 770f, 890f, 28f, if (forgotByPhone) green else gray, semibold, Paint.Align.CENTER)
        text(canvas, if (forgotByPhone) "Số điện thoại đã đăng ký" else "Email đã đăng ký", 96f, 1045f, 30f, darkGreen, bold)
        field(canvas, 96f, 1075f, 984f, 1225f)
        fill(canvas, 96f, 1330f, 984f, 1455f, green, 12f)
        text(canvas, "Tiếp tục", 540f, 1410f, 31f, Color.WHITE, bold, Paint.Align.CENTER)
        wrapped(canvas, "Bạn gặp vấn đề hoặc cần hỗ trợ? Vui lòng liên hệ với chúng tôi qua Zalo hoặc Fanpage để được hỗ trợ", 540f, 1650f, 900f, 25f, gray, regular, 36f, 4, Paint.Align.CENTER)
        stroke(canvas, 48f, 1780f, 520f, 1895f, green, 12f, 2f)
        stroke(canvas, 560f, 1780f, 1032f, 1895f, green, 12f, 2f)
        text(canvas, "Fanpage", 284f, 1852f, 28f, green, semibold, Paint.Align.CENTER)
        text(canvas, "Zalo", 796f, 1852f, 28f, green, semibold, Paint.Align.CENTER)
    }

    private fun drawSearch(canvas: Canvas) {
        background(canvas, Color.WHITE)
        fill(canvas, 0f, 0f, 1080f, 112f, Color.rgb(33, 154, 105))
        imageRegion(canvas, R.drawable.search_runtime_reference, Rect(0, 112, 1080, 2340), RectF(0f, 112f, 1080f, 2340f))
        fill(canvas, 125f, 250f, 1028f, 368f, Color.WHITE)
    }

    private fun drawAdvancedSearch(canvas: Canvas) {
        background(canvas, mint)
        if (selectedSport == 0) {
            imageRegion(canvas, R.drawable.advanced_runtime_reference, Rect(0, 112, 1080, 2340), RectF(0f, 112f, 1080f, 2340f))
            return
        }
        drawTopBack(canvas, "Tìm kiếm")
        image(canvas, R.drawable.icon_arrow_right, RectF(990f, 150f, 1030f, 195f), crop = false, tint = brightGreen)
        fill(canvas, 28f, 280f, 1052f, 1500f, Color.WHITE, 24f)
        text(canvas, "Môn thể thao", 55f, 360f, 34f, darkGreen, bold)
        stroke(canvas, 820f, 310f, 1025f, 375f, teal, 32f, 3f)
        text(canvas, "+  Tất cả", 922f, 352f, 27f, teal, semibold, Paint.Align.CENTER)
        val sports = arrayOf("Pickleball", "Cầu lông", "Bóng đá", "Bóng rổ", "Quần vợt", "Đa năng", "Typti", "Lướt ván", "B.Chuyền", "Golf", "Padel")
        sports.forEachIndexed { index, label ->
            val col = index % 4
            val row = index / 4
            val x = 55f + col * 245f
            val y = 420f + row * 86f
            stroke(canvas, x, y, x + 215f, y + 62f, if (selectedSport == index + 1) green else Color.rgb(150 + col * 18, 205, 220 - col * 16), 31f, 3f)
            text(canvas, label, x + 107f, y + 41f, 23f, if (selectedSport == index + 1) green else Color.rgb(90, 150 + col * 20, 170), regular, Paint.Align.CENTER)
        }
        text(canvas, "Loại lịch", 55f, 755f, 34f, darkGreen, bold)
        val kinds = arrayOf("Sân trống", "Sự kiện / Xé vé", "Ghép đội")
        kinds.forEachIndexed { index, label ->
            val x = 55f + index * 335f
            stroke(canvas, x, 805f, x + 300f, 890f, Color.rgb(225, 230, 225), 12f, 3f)
            text(canvas, label, x + 150f, 860f, 25f, Color.rgb(150, 150, 150), regular, Paint.Align.CENTER)
        }
        text(canvas, "Khu vực", 155f, 990f, 32f, darkGreen, bold)
        stroke(canvas, 55f, 950f, 98f, 993f, brightGreen, 22f, 5f)
        field(canvas, 55f, 1020f, 890f, 1120f)
        text(canvas, "Tỉnh/TP", 190f, 1085f, 25f, Color.rgb(180, 180, 183))
        text(canvas, "Phường/Xã", 570f, 1085f, 25f, Color.rgb(180, 180, 183))
        stroke(canvas, 920f, 1020f, 1025f, 1120f, Color.rgb(180, 180, 180), 10f, 2f)
        image(canvas, R.drawable.icon_heart, RectF(945f, 1045f, 1000f, 1100f), crop = false, tint = green)
        text(canvas, "Khoảng cách", 155f, 1215f, 32f, darkGreen, bold)
        stroke(canvas, 55f, 1175f, 98f, 1218f, Color.rgb(170, 170, 170), 22f, 4f)
        text(canvas, "Từ khóa", 55f, 1340f, 34f, darkGreen, bold)
        val keywords = arrayOf("Pickleball gần tôi", "Cầu lông gần tôi", "Xé vé gần tôi")
        keywords.forEachIndexed { index, label ->
            val x = 55f + index * 326f
            fill(canvas, x, 1385f, x + 300f, 1465f, Color.rgb(247, 249, 248), 10f)
            text(canvas, label, x + 150f, 1438f, 23f, darkGreen, regular, Paint.Align.CENTER)
        }
        fill(canvas, 24f, 2200f, 1056f, 2320f, brightGreen, 10f)
        text(canvas, "Tìm kiếm", 540f, 2278f, 34f, Color.WHITE, bold, Paint.Align.CENTER)
    }

    private fun drawVenueDetail(canvas: Canvas) {
        if (venueTab in 0..4) {
            background(canvas, Color.WHITE)
            fill(canvas, 0f, 0f, 1080f, 112f, darkGreen)
            val references = intArrayOf(
                R.drawable.venue_info_runtime_reference,
                R.drawable.venue_membership_runtime_reference,
                R.drawable.venue_services_runtime_reference,
                R.drawable.venue_images_runtime_reference,
                R.drawable.venue_rules_runtime_reference
            )
            imageRegion(canvas, references[venueTab], Rect(0, 112, 1080, 2340), RectF(0f, 112f, 1080f, 2340f))
            return
        }
        background(canvas, Color.rgb(32, 32, 34))
        image(canvas, R.drawable.pickleball_cover, RectF(0f, 0f, 1080f, 1460f), crop = true)
        fill(canvas, 0f, 0f, 1080f, 1180f, Color.argb(90, 0, 0, 0))
        fill(canvas, 25f, 1180f, 125f, 1280f, Color.WHITE, 50f)
        image(canvas, R.drawable.icon_back, RectF(55f, 1212f, 93f, 1252f), crop = false, tint = green)
        fill(canvas, 560f, 1180f, 660f, 1280f, Color.WHITE, 50f)
        image(canvas, R.drawable.icon_route, RectF(587f, 1207f, 633f, 1253f), crop = false, tint = green)
        fill(canvas, 680f, 1180f, 780f, 1280f, Color.WHITE, 50f)
        image(canvas, R.drawable.icon_heart, RectF(707f, 1207f, 753f, 1253f), crop = false, tint = green)
        fill(canvas, 805f, 1180f, 1055f, 1280f, gold, 18f)
        text(canvas, "Đặt lịch", 930f, 1245f, 31f, Color.WHITE, semibold, Paint.Align.CENTER)
        fill(canvas, 300f, 1320f, 780f, 1435f, teal, 58f)
        image(canvas, R.drawable.icon_star, RectF(340f, 1345f, 395f, 1400f), crop = false, tint = Color.WHITE)
        text(canvas, "Chưa có đánh giá", 570f, 1390f, 31f, Color.WHITE, regular, Paint.Align.CENTER)
        fill(canvas, 24f, 1420f, 1056f, 1950f, Color.rgb(248, 255, 252), 22f)
        image(canvas, R.drawable.alobo_logo, RectF(55f, 1490f, 190f, 1625f), crop = false)
        text(canvas, "Pickleball Vera Diamond", 220f, 1535f, 34f, darkGreen, bold)
        stroke(canvas, 220f, 1560f, 425f, 1625f, Color.rgb(66, 145, 255), 32f, 2f)
        text(canvas, "Pickleball", 320f, 1603f, 23f, Color.rgb(66, 145, 255), regular, Paint.Align.CENTER)
        line(canvas, 24f, 1660f, 1056f, 1660f, Color.rgb(220, 230, 225), 1f)
        image(canvas, R.drawable.nav_map, RectF(55f, 1700f, 100f, 1745f), crop = false, tint = green)
        text(canvas, "Đại lộ Hòa Bình, P. Hải Hòa, TP. Móng Cái, Quảng Ninh", 130f, 1738f, 26f, darkGreen)
        image(canvas, R.drawable.icon_calendar, RectF(55f, 1780f, 100f, 1825f), crop = false, tint = green)
        text(canvas, "05:00 - 23:00", 130f, 1818f, 26f, darkGreen)
        text(canvas, "Liên hệ", 130f, 1890f, 26f, teal)
        drawVenueTabs(canvas)
    }

    private fun drawVenueTabs(canvas: Canvas) {
        fill(canvas, 0f, 1950f, 1080f, 2400f, Color.WHITE)
        val tabs = arrayOf("Thông tin", "Gói hội viên", "Dịch vụ", "Hình ảnh", "Điều khoản", "Đánh giá")
        val centers = floatArrayOf(100f, 315f, 520f, 720f, 900f, 1080f)
        tabs.forEachIndexed { index, label ->
            text(canvas, label, centers[index], 2040f, 25f, if (venueTab == index) teal else darkGreen, if (venueTab == index) semibold else regular, Paint.Align.CENTER)
            if (venueTab == index) fill(canvas, centers[index] - 90f, 2070f, centers[index] + 90f, 2074f, teal)
        }
        when (venueTab) {
            0 -> {
                text(canvas, "Link đặt lịch online", 24f, 2160f, 33f, Color.rgb(232, 76, 20), bold)
                text(canvas, "https://datlich.alobo.vn/san/sport_pickleball_vera_diamond", 24f, 2235f, 25f, green)
            }
            1 -> text(canvas, "Chưa có gói hội viên", 540f, 2220f, 28f, gray, regular, Paint.Align.CENTER)
            2 -> {
                text(canvas, "BẢNG GIÁ SÂN", 24f, 2160f, 33f, green, bold)
                text(canvas, "Pickleball", 540f, 2230f, 28f, darkGreen, semibold, Paint.Align.CENTER)
            }
            3 -> text(canvas, "Sơ đồ sân", 24f, 2170f, 33f, green, bold)
            4 -> text(canvas, "Điều khoản và quy định của sân", 24f, 2170f, 30f, green, bold)
            else -> text(canvas, "Chưa có đánh giá", 540f, 2220f, 28f, gray, regular, Paint.Align.CENTER)
        }
    }

    private fun drawEventDetail(canvas: Canvas) {
        background(canvas, Color.WHITE)
        drawTopBack(canvas, "Sự kiện", true)
        image(canvas, R.drawable.pickleball_cover, RectF(0f, 260f, 1080f, 900f), crop = true)
        fill(canvas, 35f, 820f, 1045f, 1680f, Color.WHITE, 25f)
        text(canvas, "SỰ KIỆN PICKLEBALL MỚI", 70f, 930f, 38f, darkGreen, bold)
        text(canvas, "Renix Pickleball Club", 70f, 990f, 28f, gray)
        text(canvas, "17:00 - 21:00  |  21/09/2026", 70f, 1070f, 28f, darkGreen, semibold)
        text(canvas, "Sân 2 - 3", 70f, 1140f, 28f, darkGreen)
        text(canvas, "60.000 đ / vé", 70f, 1230f, 38f, Color.rgb(239, 143, 0), bold)
        wrapped(canvas, "Tham gia giao lưu Pickleball, vé đã bao gồm một chai nước. Số lượng người chơi có hạn.", 70f, 1330f, 900f, 27f, gray, regular, 39f, 5)
        fill(canvas, 55f, 1740f, 1025f, 1865f, green, 15f)
        text(canvas, "MUA VÉ", 540f, 1820f, 32f, Color.WHITE, bold, Paint.Align.CENTER)
    }

    private fun drawBookingSchedule(canvas: Canvas) {
        background(canvas, mint)
        fill(canvas, 0f, 0f, 1080f, 112f, green)
        imageRegion(canvas, R.drawable.booking_runtime_reference, Rect(0, 112, 1080, 2340), RectF(0f, 112f, 1080f, 2340f))
        return
        @Suppress("UNREACHABLE_CODE")
        fill(canvas, 0f, 0f, 1080f, 540f, green)
        image(canvas, R.drawable.icon_back, RectF(52f, 145f, 84f, 195f), crop = false, tint = Color.WHITE)
        text(canvas, "ĐẶT LỊCH THEO SÂN - TRỰC QUAN", 580f, 205f, 39f, Color.WHITE, bold, Paint.Align.CENTER)
        fill(canvas, 675f, 250f, 1055f, 335f, Color.rgb(130, 192, 160), 12f)
        text(canvas, "21/09/2026", 805f, 307f, 30f, Color.WHITE, regular, Paint.Align.CENTER)
        image(canvas, R.drawable.icon_calendar, RectF(965f, 270f, 1010f, 315f), crop = false, tint = Color.WHITE)
        val states = arrayOf("Trống", "Đã đặt", "Khoá", "Sự kiện")
        val colors = intArrayOf(Color.WHITE, Color.rgb(255, 91, 99), Color.rgb(160, 160, 160), Color.rgb(224, 100, 220))
        states.forEachIndexed { index, label ->
            val x = 25f + index * 245f
            fill(canvas, x, 365f, x + 58f, 423f, colors[index], 10f)
            text(canvas, label, x + 85f, 408f, 26f, Color.WHITE, semibold)
        }
        text(canvas, "Xem sân & bảng giá", 24f, 495f, 27f, Color.rgb(255, 227, 80), bold)
        fill(canvas, 0f, 540f, 1080f, 665f, Color.rgb(238, 255, 247))
        wrapped(canvas, "Lưu ý: Nếu bạn cần đặt lịch cố định vui lòng liên hệ: 0948.570.088 để được hỗ trợ", 24f, 590f, 1020f, 25f, Color.rgb(15, 90, 55), regular, 35f, 3)
        fill(canvas, 0f, 665f, 1080f, 760f, Color.rgb(172, 235, 246))
        val times = arrayOf("17:00", "17:30", "18:00", "18:30", "19:00", "19:30", "20:00", "20:30", "21:00", "21:30", "22:00")
        times.forEachIndexed { index, value -> text(canvas, value, 50f + index * 96f, 720f, 22f, darkGreen, regular, Paint.Align.CENTER) }
        for (row in 0..3) {
            val y = 760f + row * 95f
            fill(canvas, 0f, y, 145f, y + 95f, Color.rgb(206, 245, 226))
            text(canvas, "Pickleball ${row + 1}", 72f, y + 58f, 21f, darkGreen, regular, Paint.Align.CENTER)
            for (col in 0..10) {
                stroke(canvas, 145f + col * 96f, y, 241f + col * 96f, y + 95f, Color.rgb(175, 175, 175), 0f, 1f)
            }
        }
        fill(canvas, 335f, 2050f, 1055f, 2170f, Color.WHITE, 55f)
        line(canvas, 395f, 2110f, 995f, 2110f, Color.rgb(200, 225, 245), 5f)
        fill(canvas, 545f, 2085f, 595f, 2135f, Color.rgb(73, 179, 82), 25f)
        fill(canvas, 24f, 2220f, 1056f, 2340f, gold, 10f)
        text(canvas, "TIẾP THEO", 540f, 2298f, 32f, Color.WHITE, bold, Paint.Align.CENTER)
    }

    private fun drawQrScanner(canvas: Canvas) {
        background(canvas, Color.rgb(0, 57, 32))
        fill(canvas, 0f, 0f, 1080f, 112f, green)
        imageRegion(canvas, R.drawable.qr_runtime_reference, Rect(0, 112, 1080, 2340), RectF(0f, 112f, 1080f, 2340f))
        return
        @Suppress("UNREACHABLE_CODE")
        fill(canvas, 0f, 0f, 1080f, 255f, green)
        image(canvas, R.drawable.icon_back, RectF(52f, 145f, 84f, 195f), crop = false, tint = Color.WHITE)
        text(canvas, "QR Scanner", 540f, 205f, 43f, Color.WHITE, bold, Paint.Align.CENTER)
        image(canvas, R.drawable.icon_image, RectF(990f, 145f, 1035f, 195f), crop = false, tint = Color.WHITE)
        stroke(canvas, 150f, 900f, 930f, 1680f, Color.WHITE, 42f, 5f)
        line(canvas, 175f, 1100f, 905f, 1100f, Color.rgb(28, 157, 93), 5f)
        fill(canvas, 0f, 2140f, 1080f, 2400f, Color.rgb(0, 43, 25))
        text(canvas, "Đặt mã QR vào khung để quét", 540f, 2285f, 29f, Color.WHITE, regular, Paint.Align.CENTER)
    }

    private fun drawTerms(canvas: Canvas) {
        background(canvas, Color.WHITE)
        imageRegion(canvas, R.drawable.terms_runtime_reference, Rect(0, 112, 1080, 2340), RectF(0f, 112f, 1080f, 2340f))
        return
        @Suppress("UNREACHABLE_CODE")
        drawTopBack(canvas, "ĐIỀU KHOẢN SỬ DỤNG")
        text(canvas, "Cập nhật lần cuối 26/07/2025", 40f, 290f, 26f, Color.rgb(145, 145, 145))
        stroke(canvas, 805f, 250f, 1040f, 315f, Color.rgb(135, 195, 164), 14f, 2f)
        text(canvas, "Mục lục", 915f, 293f, 25f, green, regular, Paint.Align.CENTER)
        line(canvas, 0f, 335f, 1080f, 335f, Color.rgb(220, 220, 220), 2f)
        text(canvas, "ĐIỀU KHOẢN CHUNG", 70f, 445f, 36f, green, bold)
        text(canvas, "Giới thiệu và Phạm vi áp dụng", 70f, 520f, 33f, Color.rgb(20, 20, 22), bold)
        val body = "Chào mừng bạn đến với ALOBO - Tìm kiếm và đặt lịch. Ứng dụng là nền tảng di động do CÔNG TY TNHH DOCUMENT EASY sở hữu và vận hành. Mục đích của Ứng dụng là kết nối người dùng có nhu cầu tìm kiếm và đặt sân thể thao với các chủ sân trên khắp cả nước một cách thuận tiện và nhanh chóng."
        wrapped(canvas, body, 110f, 595f, 880f, 27f, Color.rgb(40, 40, 42), regular, 45f, 12)
        val body2 = "Các Điều khoản và Điều kiện sử dụng này áp dụng cho tất cả cá nhân và tổ chức truy cập, sử dụng hoặc đăng ký tài khoản trên Ứng dụng. Bằng cách sử dụng Ứng dụng, bạn xác nhận rằng bạn đã đọc, hiểu và đồng ý tuân thủ toàn bộ các Điều khoản này."
        wrapped(canvas, body2, 110f, 1160f, 880f, 27f, Color.rgb(40, 40, 42), regular, 45f, 12)
        text(canvas, "Thay đổi Điều khoản", 70f, 1835f, 33f, Color.rgb(20, 20, 22), bold)
        fill(canvas, 38f, 2200f, 1042f, 2320f, Color.rgb(67, 172, 72), 18f)
        text(canvas, "Đồng ý", 540f, 2278f, 34f, Color.WHITE, bold, Paint.Align.CENTER)
    }

    private fun drawOverlay(canvas: Canvas) {
        if (overlay != Overlay.TERMS_TOC) {
            fill(canvas, 0f, 0f, 1080f, 2400f, Color.argb(145, 0, 0, 0))
        }
        when (overlay) {
            Overlay.LOGIN_REQUIRED -> {
                imageRegion(
                    canvas,
                    R.drawable.login_required_runtime_reference,
                    Rect(108, 866, 972, 1232),
                    RectF(108f, 866f, 972f, 1232f)
                )
            }
            Overlay.LANGUAGE -> {
                fill(canvas, 180f, 1065f, 900f, 1385f, Color.WHITE, 22f)
                text(canvas, "Tiếng Việt", 540f, 1170f, 31f, green, semibold, Paint.Align.CENTER)
                line(canvas, 180f, 1225f, 900f, 1225f, Color.rgb(225, 225, 225), 1f)
                text(canvas, "English", 540f, 1325f, 31f, green, semibold, Paint.Align.CENTER)
            }
            Overlay.BOOKING_CHOICE -> {
                imageRegion(
                    canvas,
                    R.drawable.booking_choice_runtime_reference,
                    Rect(25, 850, 1055, 1605),
                    RectF(25f, 850f, 1055f, 1605f)
                )
            }
            Overlay.DATE_PICKER -> {
                fill(canvas, 100f, 650f, 980f, 1570f, Color.WHITE, 24f)
                text(canvas, "Chọn ngày", 540f, 750f, 36f, darkGreen, bold, Paint.Align.CENTER)
                text(canvas, "Tháng 09 / 2026", 540f, 850f, 30f, darkGreen, semibold, Paint.Align.CENTER)
                val days = arrayOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
                days.forEachIndexed { i, day -> text(canvas, day, 175f + i * 122f, 940f, 24f, gray, semibold, Paint.Align.CENTER) }
                for (day in 1..30) {
                    val index = day - 1
                    val x = 175f + (index % 7) * 122f
                    val y = 1030f + (index / 7) * 90f
                    if (day == 21) fill(canvas, x - 35f, y - 50f, x + 35f, y + 20f, green, 35f)
                    text(canvas, day.toString(), x, y, 24f, if (day == 21) Color.WHITE else Color.rgb(45, 45, 48), regular, Paint.Align.CENTER)
                }
                fill(canvas, 650f, 1450f, 930f, 1530f, green, 12f)
                text(canvas, "Áp dụng", 790f, 1503f, 26f, Color.WHITE, semibold, Paint.Align.CENTER)
            }
            Overlay.TERMS_TOC -> {
                imageRegion(
                    canvas,
                    R.drawable.terms_toc_runtime_reference,
                    Rect(0, 112, 1080, 2340),
                    RectF(0f, 112f, 1080f, 2340f)
                )
            }
            Overlay.NONE -> Unit
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x * 1080f / width
        val y = event.y * 1080f / width
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                dragStartOffset = scrollOffset()
                isDragging = false
                pressedX = x
                pressedY = y
                isPressedTarget = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (overlay == Overlay.NONE && scrollMax() > 0f) {
                    val distance = downY - y
                    if (abs(distance) > 12f) isDragging = true
                    if (isDragging) {
                        isPressedTarget = false
                        scrollOffsets[screen] = (dragStartOffset + distance).coerceIn(0f, scrollMax())
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val isTap = !isDragging && abs(x - downX) < 45f && abs(y - downY) < 45f
                isPressedTarget = false
                isDragging = false
                invalidate()
                if (isTap) {
                    performClick()
                    handleTap(x, y)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressedTarget = false
                isDragging = false
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun getAccessibilityClassName(): CharSequence = AloboView::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = AloboView::class.java.name
        info.isScrollable = scrollMax() > 0f
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = AloboView::class.java.name
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider {
        Logger.d("ACCESSIBILITY_PROVIDER", "screen=${screen.name}")
        return accessibilityProvider
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!manager.isEnabled || !manager.isTouchExplorationEnabled) return super.dispatchHoverEvent(event)
        val x = event.x * 1080f / width
        val y = event.y * 1080f / width
        val targetId = accessibilityTargets().firstOrNull { it.bounds.contains(x, y) }?.id ?: View.NO_ID
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (hoveredVirtualId != targetId) {
                    if (hoveredVirtualId != View.NO_ID) sendVirtualEvent(hoveredVirtualId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    hoveredVirtualId = targetId
                    if (targetId != View.NO_ID) sendVirtualEvent(targetId, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hoveredVirtualId != View.NO_ID) sendVirtualEvent(hoveredVirtualId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                hoveredVirtualId = View.NO_ID
            }
        }
        return targetId != View.NO_ID
    }

    private val accessibilityProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            val targets = accessibilityTargets()
            if (virtualViewId == HOST_VIEW_ID) {
                return AccessibilityNodeInfo.obtain(this@AloboView).apply {
                    className = AloboView::class.java.name
                    packageName = context.packageName
                    isVisibleToUser = true
                    isScrollable = scrollMax() > 0f
                    targets.forEach { addChild(this@AloboView, it.id) }
                }
            }
            val target = targets.firstOrNull { it.id == virtualViewId } ?: return null
            val scale = width / 1080f
            val parentBounds = Rect(
                (target.bounds.left * scale).toInt(),
                (target.bounds.top * scale).toInt(),
                (target.bounds.right * scale).toInt(),
                (target.bounds.bottom * scale).toInt()
            )
            val location = IntArray(2).also { getLocationOnScreen(it) }
            return AccessibilityNodeInfo.obtain().apply {
                setSource(this@AloboView, virtualViewId)
                setParent(this@AloboView)
                packageName = context.packageName
                className = android.widget.Button::class.java.name
                text = target.label
                contentDescription = target.label
                isEnabled = true
                isVisibleToUser = true
                isClickable = true
                isFocusable = true
                isSelected = target.selected
                setBoundsInParent(parentBounds)
                setBoundsInScreen(Rect(parentBounds).apply { offset(location[0], location[1]) })
                addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                if (accessibilityFocusedId == virtualViewId) {
                    isAccessibilityFocused = true
                    addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                } else {
                    addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
                }
            }
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val target = accessibilityTargets().firstOrNull { it.id == virtualViewId } ?: return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    performClick()
                    handleTap(target.bounds.centerX(), target.bounds.centerY())
                    sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    true
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocusedId == virtualViewId) false else {
                        accessibilityFocusedId = virtualViewId
                        invalidate()
                        sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                        true
                    }
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocusedId != virtualViewId) false else {
                        accessibilityFocusedId = View.NO_ID
                        invalidate()
                        sendVirtualEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                        true
                    }
                }
                else -> false
            }
        }
    }

    private fun sendVirtualEvent(virtualId: Int, eventType: Int) {
        val target = accessibilityTargets().firstOrNull { it.id == virtualId } ?: return
        val event = AccessibilityEvent.obtain(eventType).apply {
            packageName = context.packageName
            className = android.widget.Button::class.java.name
            contentDescription = target.label
            isEnabled = true
            setSource(this@AloboView, virtualId)
        }
        parent?.requestSendAccessibilityEvent(this, event)
    }

    fun semanticTargets(): List<SemanticTarget> = accessibilityTargets()

    fun activateSemantic(id: Int) {
        val target = accessibilityTargets().firstOrNull { it.id == id } ?: return
        performClick()
        handleTap(target.bounds.centerX(), target.bounds.centerY())
    }

    private fun accessibilityTargets(): List<SemanticTarget> {
        fun target(key: String, label: String, l: Float, t: Float, r: Float, b: Float, selected: Boolean = false) =
            SemanticTarget(key.hashCode() and Int.MAX_VALUE, key, label, RectF(l, t, r, b), selected)

        if (overlay != Overlay.NONE) return when (overlay) {
            Overlay.LOGIN_REQUIRED -> listOf(
                target("login_required_close", "Đóng", 850f, 850f, 990f, 990f),
                target("login_required_ok", "Đăng nhập", 120f, 1080f, 960f, 1235f)
            )
            Overlay.LANGUAGE -> listOf(
                target("language_vietnamese", "Tiếng Việt", 170f, 1040f, 910f, 1225f),
                target("language_english", "English", 170f, 1225f, 910f, 1410f)
            )
            Overlay.BOOKING_CHOICE -> listOf(
                target("booking_choice_close", "Đóng", 930f, 830f, 1040f, 960f),
                target("booking_visual", "Đặt lịch trực quan", 50f, 1010f, 1030f, 1290f),
                target("booking_social", "Đặt lịch cộng đồng", 50f, 1290f, 1030f, 1570f)
            )
            Overlay.DATE_PICKER -> listOf(target("date_apply", "Áp dụng ngày", 630f, 1410f, 960f, 1560f))
            Overlay.TERMS_TOC -> listOf(target("terms_toc_item", "Mục điều khoản", 430f, 130f, 1080f, 2340f))
            Overlay.NONE -> emptyList()
        }

        val targets = when (screen) {
            Screen.ONBOARDING -> listOf(
                target("skip_onboarding", "Bỏ qua", 780f, 120f, 1070f, 300f),
                target("continue_onboarding", if (onboardingPage == 3) "Bắt đầu" else "Tiếp tục", 80f, 2160f, 1000f, 2360f)
            )
            Screen.HOME -> listOf(
                target("date_header", "Chọn ngày", 180f, 120f, 560f, 225f),
                target("language_header", "Ngôn ngữ", 930f, 100f, 1080f, 240f),
                target("login_header", "Đăng nhập", 190f, 220f, 500f, 320f),
                target("register_header", "Đăng ký", 500f, 220f, 825f, 320f),
                target("search_box", "Tìm kiếm sân", 24f, 320f, 700f, 470f),
                target("qr_scanner", "Quét mã QR", 700f, 320f, 855f, 470f),
                target("favorites", "Sân yêu thích", 900f, 320f, 1080f, 470f),
                target("quick_filter", "Bộ lọc tìm nhanh", 20f, 475f, 1050f, 600f),
                target("sport_pick_1", "Chọn Pickleball", 0f, 600f, 216f, 790f),
                target("sport_pick_2", "Chọn Cầu lông", 216f, 600f, 432f, 790f),
                target("sport_pick_3", "Chọn Bóng đá", 432f, 600f, 648f, 790f),
                target("sport_pick_4", "Chọn Bóng rổ", 648f, 600f, 864f, 790f),
                target("sport_pick_5", "Chọn Quần vợt", 864f, 600f, 1080f, 790f),
                target("venue_card", "Chi tiết sân", 20f, 790f, 1080f, 1700f),
                target("book_venue_card", "Đặt lịch sân", 820f, 1140f, 1060f, 1300f)
            )
            Screen.MAP -> listOf(
                target("map_search", "Tìm kiếm trên bản đồ", 20f, 140f, 1080f, 270f),
                target("map_sport_filter", "Lọc môn thể thao", 20f, 275f, 1080f, 410f),
                target("map_my_location", "Vị trí của tôi", 860f, 760f, 1030f, 940f),
                target("map_venue_item", "Chi tiết sân", 0f, 1050f, 900f, 2050f),
                target("map_route", "Chỉ đường", 900f, 1050f, 1080f, 2050f)
            )
            Screen.EXPLORE -> listOf(
                target("explore_all", "Tất cả", 0f, 220f, 200f, 340f, exploreFilter == 0),
                target("explore_events", "Sự kiện", 200f, 220f, 470f, 340f, exploreFilter == 1),
                target("explore_membership", "Gói hội viên", 470f, 220f, 820f, 340f, exploreFilter == 2),
                target("explore_courses", "Khóa học", 820f, 220f, 1080f, 340f, exploreFilter == 3)
            ) + when (exploreFilter) {
                1 -> listOf(
                    target("explore_event_details", "Xem chi tiết sự kiện", 65f, 1290f, 400f, 1450f),
                    target("explore_buy_ticket", "Mua vé sự kiện", 720f, 1260f, 1040f, 1470f),
                    target("explore_share_event", "Chia sẻ sự kiện", 900f, 570f, 1060f, 770f)
                )
                2 -> listOf(
                    target("explore_venue_header", "Chi tiết sân", 20f, 340f, 1060f, 570f),
                    target("explore_membership_register", "Đăng ký gói hội viên", 700f, 900f, 1040f, 1750f)
                )
                else -> listOf(
                    target("explore_venue_header", "Chi tiết sân", 20f, 340f, 1060f, 570f),
                    target("explore_contact", "Liên hệ", 700f, 1000f, 1040f, 1240f)
                )
            }
            Screen.FEATURED -> listOf(target("featured_banner", "Đặt lịch ưu đãi nổi bật", 20f, 130f, 1060f, 2110f))
            Screen.ACCOUNT -> listOf(
                target("account_login", "Đăng nhập", 250f, 580f, 560f, 715f),
                target("account_register", "Đăng ký", 570f, 580f, 900f, 715f),
                target("booking_history", "Danh sách lịch đã đặt", 40f, 790f, 1040f, 940f),
                target("version_info", "Thông tin phiên bản 2.10.1", 40f, 1015f, 1040f, 1148f),
                target("terms_policy", "Điều khoản và chính sách", 40f, 1148f, 1040f, 1271f),
                target("whats_new", "Ứng dụng có gì mới", 40f, 1271f, 1040f, 1394f),
                target("language_setting", "Ngôn ngữ", 40f, 1394f, 1040f, 1525f)
            )
            Screen.LOGIN -> listOf(
                target("back_login", "Quay lại", 0f, 100f, 140f, 260f),
                target("login_phone_tab", "Đăng nhập bằng số điện thoại", 40f, 250f, 540f, 420f, !loginByEmail),
                target("login_email_tab", "Đăng nhập bằng email", 540f, 250f, 1040f, 420f, loginByEmail),
                target("login_country_code", "Mã vùng Việt Nam +84", 115f, 575f, 335f, 735f),
                target("login_password_visibility", "Hiện hoặc ẩn mật khẩu", 850f, 875f, 984f, 1015f),
                target("login_submit", "Đăng nhập", 90f, 1080f, 990f, 1235f),
                target("biometric_login", "Đăng nhập với sinh trắc học", 90f, 1240f, 990f, 1410f),
                target("forgot_password", "Quên mật khẩu", 520f, 1420f, 900f, 1550f),
                target("register_from_login", "Đăng ký tài khoản", 600f, 1620f, 900f, 1760f),
                target("google_login", "Đăng nhập với Google", 80f, 1770f, 1000f, 1930f),
                target("owner_app_link", "Tải ứng dụng quản lý sân", 40f, 1980f, 1040f, 2340f)
            ).filterNot { loginByEmail && it.label.startsWith("Mã vùng") }
            Screen.REGISTER -> listOf(
                target("back_register", "Quay lại", 0f, 100f, 140f, 260f),
                target("register_country_code", "Mã vùng Việt Nam +84", 115f, 450f, 335f, 595f),
                target("register_email_clear", "Xóa email", 850f, 735f, 984f, 880f),
                target("register_name_clear", "Xóa họ tên", 850f, 1035f, 984f, 1180f),
                target("register_password_visibility", "Hiện hoặc ẩn mật khẩu", 850f, 1335f, 984f, 1480f),
                target("register_confirm_visibility", "Hiện hoặc ẩn mật khẩu xác nhận", 850f, 1635f, 984f, 1780f),
                target("register_submit", "Đăng ký", 90f, 1880f, 990f, 2050f),
                target("login_from_register", "Đăng nhập", 570f, 2070f, 900f, 2240f)
            )
            Screen.FORGOT_PASSWORD -> listOf(
                target("back_forgot", "Quay lại", 0f, 100f, 140f, 260f),
                target("forgot_email_tab", "Tìm bằng email", 80f, 800f, 540f, 950f, !forgotByPhone),
                target("forgot_phone_tab", "Tìm bằng số điện thoại", 540f, 800f, 1000f, 950f, forgotByPhone),
                target("forgot_continue", "Tiếp tục", 90f, 1310f, 990f, 1470f),
                target("fanpage_support", "Hỗ trợ qua Fanpage", 40f, 1760f, 540f, 1920f),
                target("zalo_support", "Hỗ trợ qua Zalo", 540f, 1760f, 1040f, 1920f)
            )
            Screen.SEARCH -> listOf(
                target("back_search", "Quay lại", 0f, 100f, 140f, 260f),
                target("advanced_filter", "Bộ lọc nâng cao", 930f, 100f, 1080f, 260f),
                target("search_venue_item", "Chi tiết kết quả tìm kiếm", 20f, 380f, 800f, 2150f),
                target("search_favorite", "Yêu thích sân", 800f, 380f, 930f, 2150f),
                target("search_route", "Chỉ đường", 930f, 380f, 1080f, 2150f)
            )
            Screen.ADVANCED_SEARCH -> listOf(
                target("back_advanced", "Quay lại", 0f, 100f, 140f, 260f),
                target("reset_filters", "Đặt lại bộ lọc", 930f, 100f, 1080f, 260f),
                target("sport_filter", "Chọn môn thể thao", 40f, 400f, 1040f, 700f),
                target("saved_area", "Khu vực đã lưu", 900f, 1000f, 1040f, 1140f),
                target("advanced_search_submit", "Tìm kiếm", 20f, 2160f, 1060f, 2350f)
            )
            Screen.VENUE_DETAIL -> listOf(
                target("back_venue", "Quay lại", 10f, 1140f, 145f, 1430f),
                target("venue_route", "Chỉ đường", 535f, 1140f, 675f, 1430f),
                target("venue_favorite", "Yêu thích", 665f, 1140f, 800f, 1430f),
                target("venue_book", "Đặt lịch", 790f, 1140f, 1070f, 1430f),
                target("venue_contact", "Liên hệ sân", 90f, 1820f, 350f, 1940f),
                target("venue_info_tab", "Thông tin", 0f, 1940f, 190f, 2120f, venueTab == 0),
                target("venue_membership_tab", "Gói hội viên", 190f, 1940f, 410f, 2120f, venueTab == 1),
                target("venue_services_tab", "Dịch vụ", 410f, 1940f, 615f, 2120f, venueTab == 2),
                target("venue_images_tab", "Hình ảnh", 615f, 1940f, 820f, 2120f, venueTab == 3),
                target("venue_rules_tab", "Điều khoản", 820f, 1940f, 954f, 2120f, venueTab == 4),
                target("venue_reviews_tab", "Đánh giá", 954f, 1940f, 1080f, 2120f, venueTab == 5)
            )
            Screen.EVENT_DETAIL -> listOf(
                target("back_event", "Quay lại", 0f, 100f, 140f, 260f),
                target("event_buy_ticket", "Mua vé", 40f, 1700f, 1040f, 1900f)
            )
            Screen.BOOKING_SCHEDULE -> listOf(
                target("back_booking", "Quay lại", 0f, 100f, 140f, 260f),
                target("booking_date", "Chọn ngày", 650f, 220f, 1070f, 360f),
                target("booking_slot", "Chọn khung giờ", 130f, 730f, 1080f, 1200f),
                target("booking_next", "Tiếp tục đặt lịch", 20f, 2180f, 1060f, 2370f)
            )
            Screen.QR_SCANNER -> listOf(
                target("back_qr", "Quay lại", 0f, 100f, 140f, 260f),
                target("qr_pick_image", "Chọn ảnh mã QR", 940f, 100f, 1080f, 260f)
            )
            Screen.TERMS -> listOf(
                target("back_terms", "Quay lại", 0f, 100f, 140f, 260f),
                target("terms_toc", "Mục lục", 760f, 220f, 1060f, 340f),
                target("terms_agree", "Đồng ý điều khoản", 20f, 2160f, 1060f, 2360f)
            )
        }.toMutableList()

        mainNavIndex()?.let { active ->
            val navOffset = viewportHeight() - 2400f
            val labels = arrayOf("Trang chủ", "Bản đồ", "Khám phá", "Nổi bật", "Tài khoản")
            val edges = floatArrayOf(0f, 230f, 445f, 635f, 840f, 1080f)
            labels.indices.forEach { index ->
                targets += target("bottom_nav_$index", labels[index], edges[index], navOffset + 2040f, edges[index + 1], viewportHeight(), active == index)
            }
        }
        return targets
    }

    private fun clicked(target: String, action: () -> Unit = {}) {
        Logger.click(target, screen.name)
        action()
    }

    private fun inBox(x: Float, y: Float, l: Float, t: Float, r: Float, b: Float) = x in l..r && y in t..b

    private fun handleTap(x: Float, y: Float) {
        if (overlay != Overlay.NONE) {
            handleOverlayTap(x, y)
            return
        }
        val navOffset = viewportHeight() - 2400f
        if (mainNavIndex() != null && y > navOffset + 2040f) {
            val target = when {
                x < 230f -> Screen.HOME
                x < 445f -> Screen.MAP
                x < 635f -> Screen.EXPLORE
                x < 840f -> Screen.FEATURED
                else -> Screen.ACCOUNT
            }
            clicked("bottom_nav_${target.name.lowercase()}") { activity.showMainTab(target) }
            return
        }
        val contentY = if (screen == Screen.MAP && y < 1050f) y else y + scrollOffset()
        when (screen) {
            Screen.ONBOARDING -> tapOnboarding(x, contentY)
            Screen.HOME -> tapHome(x, contentY)
            Screen.MAP -> tapMap(x, contentY)
            Screen.EXPLORE -> tapExplore(x, contentY)
            Screen.FEATURED -> tapFeatured(x, contentY)
            Screen.ACCOUNT -> tapAccount(x, contentY)
            Screen.LOGIN -> tapLogin(x, contentY)
            Screen.REGISTER -> tapRegister(x, contentY)
            Screen.FORGOT_PASSWORD -> tapForgot(x, contentY)
            Screen.SEARCH -> tapSearch(x, contentY)
            Screen.ADVANCED_SEARCH -> tapAdvanced(x, contentY)
            Screen.VENUE_DETAIL -> tapVenue(x, contentY)
            Screen.EVENT_DETAIL -> tapEvent(x, contentY)
            Screen.BOOKING_SCHEDULE -> tapBookingSchedule(x, contentY)
            Screen.QR_SCANNER -> tapQr(x, contentY)
            Screen.TERMS -> tapTerms(x, contentY)
        }
    }

    private fun tapOnboarding(x: Float, y: Float) {
        when {
            inBox(x, y, 780f, 120f, 1070f, 300f) -> clicked("skip_onboarding") { activity.finishOnboarding() }
            y > 2160f -> clicked("continue_onboarding_page_${onboardingPage + 1}") {
                if (onboardingPage == 3) activity.finishOnboarding() else {
                    onboardingPage++
                    invalidate()
                    activity.refreshSemantics()
                    Logger.screen("ONBOARDING_${onboardingPage + 1}")
                }
            }
            inBox(x, y, 410f, 2030f, 680f, 2170f) -> clicked("onboarding_indicator") {
                onboardingPage = ((x - 430f) / 55f).toInt().coerceIn(0, 3)
                invalidate()
                activity.refreshSemantics()
            }
        }
    }

    private fun tapHome(x: Float, y: Float) {
        when {
            inBox(x, y, 930f, 100f, 1080f, 240f) -> clicked("language_header") { activity.showOverlay(Overlay.LANGUAGE) }
            inBox(x, y, 180f, 120f, 560f, 225f) -> clicked("date_header") { activity.showOverlay(Overlay.DATE_PICKER) }
            inBox(x, y, 190f, 220f, 500f, 320f) -> clicked("login_header") { activity.showScreen(Screen.LOGIN) }
            inBox(x, y, 500f, 220f, 825f, 320f) -> clicked("register_header") { activity.showScreen(Screen.REGISTER) }
            inBox(x, y, 24f, 320f, 700f, 470f) -> clicked("search_box") { activity.showScreen(Screen.SEARCH) }
            inBox(x, y, 700f, 320f, 855f, 470f) -> clicked("qr_scanner") { activity.requestCameraAndShowScanner() }
            inBox(x, y, 900f, 320f, 1080f, 470f) -> clicked("favorites") { activity.showOverlay(Overlay.LOGIN_REQUIRED) }
            inBox(x, y, 20f, 475f, 1050f, 600f) -> clicked("quick_filter") { activity.showScreen(Screen.ADVANCED_SEARCH) }
            inBox(x, y, 0f, 600f, 1080f, 790f) -> clicked("sport_category") {
                selectedSport = (x / 216f).toInt().coerceIn(0, 4) + 1
                invalidate()
                activity.refreshSemantics()
            }
            inBox(x, y, 820f, 1140f, 1060f, 1300f) -> clicked("book_venue_card") { activity.showOverlay(Overlay.BOOKING_CHOICE) }
            inBox(x, y, 820f, 810f, 950f, 950f) -> clicked("favorite_venue_card") { activity.showOverlay(Overlay.LOGIN_REQUIRED) }
            inBox(x, y, 940f, 810f, 1080f, 950f) -> clicked("route_venue_card") { activity.openUri("geo:0,0?q=Pickleball") }
            inBox(x, y, 20f, 790f, 1080f, 1700f) -> clicked("venue_card") { activity.showScreen(Screen.VENUE_DETAIL) }
        }
    }

    private fun tapMap(x: Float, y: Float) {
        when {
            inBox(x, y, 20f, 140f, 1080f, 270f) -> clicked("map_search") { activity.showScreen(Screen.SEARCH) }
            inBox(x, y, 20f, 275f, 1080f, 410f) -> clicked("map_sport_filter") {
                selectedSport = (x / 315f).toInt() + 1
                invalidate()
                activity.refreshSemantics()
            }
            inBox(x, y, 860f, 760f, 1030f, 940f) -> clicked("map_my_location") { activity.toast("Đã cập nhật vị trí") }
            inBox(x, y, 0f, 1050f, 900f, 2050f) -> clicked("map_venue_item") { activity.showScreen(Screen.VENUE_DETAIL) }
            inBox(x, y, 900f, 1050f, 1080f, 2050f) -> clicked("map_route") { activity.openUri("geo:0,0?q=Badminton") }
        }
    }

    private fun tapExplore(x: Float, y: Float) {
        when {
            inBox(x, y, 0f, 220f, 1080f, 340f) -> clicked("explore_filter") {
                exploreFilter = when { x < 200 -> 0; x < 470 -> 1; x < 820 -> 2; else -> 3 }
                invalidate()
                activity.refreshSemantics()
            }
            exploreFilter == 1 && inBox(x, y, 65f, 1290f, 400f, 1450f) ->
                clicked("explore_event_details") { activity.showScreen(Screen.EVENT_DETAIL) }
            exploreFilter == 1 && inBox(x, y, 720f, 1260f, 1040f, 1470f) ->
                clicked("explore_buy_ticket") { activity.showOverlay(Overlay.LOGIN_REQUIRED) }
            exploreFilter == 1 && inBox(x, y, 900f, 570f, 1060f, 770f) ->
                clicked("explore_share_event") { activity.toast("Chia sẻ sự kiện") }
            exploreFilter == 2 && x > 700f && y in 900f..1750f ->
                clicked("explore_membership_register") { activity.showOverlay(Overlay.LOGIN_REQUIRED) }
            exploreFilter in listOf(0, 2, 3) && inBox(x, y, 20f, 340f, 1060f, 570f) ->
                clicked("explore_venue_header") { activity.showScreen(Screen.VENUE_DETAIL) }
            exploreFilter in listOf(0, 3) && inBox(x, y, 700f, 1000f, 1040f, 1240f) ->
                clicked("explore_contact") { activity.openUri("tel:+84394705555") }
        }
    }

    private fun tapFeatured(x: Float, y: Float) {
        if (inBox(x, y, 20f, 130f, 1060f, 2110f)) clicked("featured_banner") { activity.showOverlay(Overlay.BOOKING_CHOICE) }
    }

    private fun tapAccount(x: Float, y: Float) {
        when {
            inBox(x, y, 250f, 580f, 560f, 715f) -> clicked("account_login") { activity.showScreen(Screen.LOGIN) }
            inBox(x, y, 570f, 580f, 900f, 715f) -> clicked("account_register") { activity.showScreen(Screen.REGISTER) }
            inBox(x, y, 40f, 790f, 1040f, 940f) -> clicked("booking_history") { activity.showOverlay(Overlay.LOGIN_REQUIRED) }
            inBox(x, y, 40f, 1015f, 1040f, 1148f) -> clicked("version_info") { activity.openUri("market://details?id=com.alobo.sportUser") }
            inBox(x, y, 40f, 1148f, 1040f, 1271f) -> clicked("terms_policy") { activity.showScreen(Screen.TERMS) }
            inBox(x, y, 40f, 1271f, 1040f, 1394f) -> clicked("whats_new") { activity.openUri("market://details?id=com.alobo.sportUser") }
            inBox(x, y, 40f, 1394f, 1040f, 1525f) -> clicked("language_setting") { activity.showOverlay(Overlay.LANGUAGE) }
        }
    }

    private fun tapLogin(x: Float, y: Float) {
        when {
            inBox(x, y, 0f, 100f, 140f, 260f) -> clicked("back") { activity.goBack(Screen.ACCOUNT) }
            inBox(x, y, 40f, 250f, 1040f, 420f) -> clicked(if (x < 540f) "login_phone_tab" else "login_email_tab") {
                loginByEmail = x >= 540f
                invalidate()
                activity.refreshEditors()
            }
            !loginByEmail && inBox(x, y, 115f, 575f, 335f, 735f) -> clicked("login_country_code") {
                activity.toast("Việt Nam (+84)")
            }
            inBox(x, y, 850f, 875f, 984f, 1015f) -> clicked("login_password_visibility") {
                activity.togglePassword("login_password")
            }
            inBox(x, y, 90f, 1080f, 990f, 1235f) -> clicked("login_submit") {
                val userField = if (loginByEmail) "login_email" else "login_phone"
                if (activity.value(userField).isBlank() || activity.value("login_password").isBlank()) activity.toast("Vui lòng nhập đầy đủ thông tin")
                else activity.toast("Không thể đăng nhập ở bản demo")
            }
            inBox(x, y, 90f, 1240f, 990f, 1410f) -> clicked("biometric_login") { activity.toast("Vui lòng đăng nhập bằng mật khẩu trước") }
            inBox(x, y, 520f, 1420f, 900f, 1550f) -> clicked("forgot_password") { activity.showScreen(Screen.FORGOT_PASSWORD) }
            inBox(x, y, 600f, 1620f, 900f, 1760f) -> clicked("register_from_login") { activity.showScreen(Screen.REGISTER) }
            inBox(x, y, 80f, 1770f, 1000f, 1930f) -> clicked("google_login") { activity.toast("Đăng nhập Google chưa khả dụng") }
            inBox(x, y, 40f, 1980f, 1040f, 2340f) -> clicked("owner_app_link") { activity.openUri("market://search?q=Alobo%20quản%20lý") }
        }
    }

    private fun tapRegister(x: Float, y: Float) {
        when {
            inBox(x, y, 0f, 100f, 140f, 260f) -> clicked("back") { activity.goBack(Screen.ACCOUNT) }
            inBox(x, y, 115f, 450f, 335f, 595f) -> clicked("register_country_code") { activity.toast("Việt Nam (+84)") }
            inBox(x, y, 850f, 735f, 984f, 880f) -> clicked("register_email_clear") { activity.clearEditor("register_email") }
            inBox(x, y, 850f, 1035f, 984f, 1180f) -> clicked("register_name_clear") { activity.clearEditor("register_name") }
            inBox(x, y, 850f, 1335f, 984f, 1480f) -> clicked("register_password_visibility") { activity.togglePassword("register_password") }
            inBox(x, y, 850f, 1635f, 984f, 1780f) -> clicked("register_confirm_visibility") { activity.togglePassword("register_confirm") }
            inBox(x, y, 90f, 1880f, 990f, 2050f) -> clicked("register_submit") {
                val ids = arrayOf("register_phone", "register_email", "register_name", "register_password", "register_confirm")
                if (ids.any { activity.value(it).isBlank() }) activity.toast("Vui lòng nhập đầy đủ thông tin") else activity.toast("Không thể đăng ký ở bản demo")
            }
            inBox(x, y, 570f, 2070f, 900f, 2240f) -> clicked("login_from_register") { activity.showScreen(Screen.LOGIN) }
        }
    }

    private fun tapForgot(x: Float, y: Float) {
        when {
            inBox(x, y, 0f, 100f, 140f, 260f) -> clicked("back") { activity.goBack(Screen.LOGIN) }
            inBox(x, y, 80f, 800f, 540f, 950f) -> clicked("forgot_email_tab") { forgotByPhone = false; invalidate(); activity.refreshEditors() }
            inBox(x, y, 540f, 800f, 1000f, 950f) -> clicked("forgot_phone_tab") { forgotByPhone = true; invalidate(); activity.refreshEditors() }
            inBox(x, y, 90f, 1310f, 990f, 1470f) -> clicked("forgot_continue") {
                val userField = if (forgotByPhone) "forgot_phone" else "forgot_email"
                if (activity.value(userField).isBlank()) activity.toast("Không được để trống") else activity.toast("Không tìm thấy tài khoản")
            }
            inBox(x, y, 40f, 1760f, 540f, 1920f) -> clicked("fanpage_support") { activity.openUri("https://www.facebook.com/alobo.vn") }
            inBox(x, y, 540f, 1760f, 1040f, 1920f) -> clicked("zalo_support") { activity.openUri("https://zalo.me") }
        }
    }

    private fun tapSearch(x: Float, y: Float) {
        when {
            inBox(x, y, 0f, 100f, 140f, 260f) -> clicked("back") { activity.goBack(Screen.HOME) }
            inBox(x, y, 930f, 100f, 1080f, 260f) -> clicked("advanced_filter") { activity.showScreen(Screen.ADVANCED_SEARCH) }
            inBox(x, y, 20f, 380f, 1080f, 2150f) -> when {
                x > 930f -> clicked("search_route") { activity.openUri("geo:0,0?q=Pickleball") }
                x > 800f -> clicked("search_favorite") { activity.showOverlay(Overlay.LOGIN_REQUIRED) }
                else -> clicked("search_venue_item") { activity.showScreen(Screen.VENUE_DETAIL) }
            }
        }
    }

    private fun tapAdvanced(x: Float, y: Float) {
        when {
            inBox(x, y, 0f, 100f, 140f, 260f) -> clicked("back") { activity.goBack(Screen.SEARCH) }
            inBox(x, y, 930f, 100f, 1080f, 260f) -> clicked("reset_filters") {
                selectedSport = 0
                invalidate()
                activity.refreshSemantics()
            }
            inBox(x, y, 40f, 400f, 1040f, 700f) -> clicked("sport_filter") {
                selectedSport = (((y - 420f) / 86f).toInt() * 4 + ((x - 55f) / 245f).toInt() + 1).coerceIn(1, 11)
                invalidate()
                activity.refreshSemantics()
            }
            inBox(x, y, 20f, 2160f, 1060f, 2350f) -> clicked("advanced_search_submit") { activity.showScreen(Screen.SEARCH) }
            inBox(x, y, 900f, 1000f, 1040f, 1140f) -> clicked("saved_area") { activity.showOverlay(Overlay.LOGIN_REQUIRED) }
        }
    }

    private fun tapVenue(x: Float, y: Float) {
        when {
            inBox(x, y, 10f, 1140f, 145f, 1430f) -> clicked("back") { activity.goBack(Screen.SEARCH) }
            inBox(x, y, 535f, 1140f, 675f, 1430f) -> clicked("venue_route") { activity.openUri("geo:0,0?q=Pickleball%20Vera%20Diamond") }
            inBox(x, y, 665f, 1140f, 800f, 1430f) -> clicked("venue_favorite") { activity.showOverlay(Overlay.LOGIN_REQUIRED) }
            inBox(x, y, 790f, 1140f, 1070f, 1430f) -> clicked("venue_book") { activity.showOverlay(Overlay.BOOKING_CHOICE) }
            inBox(x, y, 90f, 1820f, 350f, 1940f) -> clicked("venue_contact") { activity.openUri("tel:0948570088") }
            inBox(x, y, 0f, 1940f, 1080f, 2120f) -> clicked("venue_tab") {
                venueTab = if (x >= 954f) 5 else (x / 205f).toInt().coerceIn(0, 4)
                invalidate()
                activity.refreshSemantics()
            }
            inBox(x, y, 20f, 2130f, 1060f, 2300f) && venueTab == 0 -> clicked("copy_booking_link") { activity.toast("Đã sao chép link") }
        }
    }

    private fun tapEvent(x: Float, y: Float) {
        when {
            inBox(x, y, 0f, 100f, 140f, 260f) -> clicked("back") { activity.goBack(Screen.EXPLORE) }
            inBox(x, y, 40f, 1700f, 1040f, 1900f) -> clicked("event_buy_ticket") { activity.showOverlay(Overlay.LOGIN_REQUIRED) }
        }
    }

    private fun tapBookingSchedule(x: Float, y: Float) {
        when {
            inBox(x, y, 0f, 100f, 140f, 260f) -> clicked("back") { activity.goBack(Screen.VENUE_DETAIL) }
            inBox(x, y, 650f, 220f, 1070f, 360f) -> clicked("booking_date") { activity.showOverlay(Overlay.DATE_PICKER) }
            inBox(x, y, 130f, 730f, 1080f, 1200f) -> clicked("booking_slot") { activity.toast("Đã chọn khung giờ") }
            inBox(x, y, 20f, 2180f, 1060f, 2370f) -> clicked("booking_next") { activity.showOverlay(Overlay.LOGIN_REQUIRED) }
        }
    }

    private fun tapQr(x: Float, y: Float) {
        when {
            inBox(x, y, 0f, 100f, 140f, 260f) -> clicked("back") { activity.goBack(Screen.HOME) }
            inBox(x, y, 940f, 100f, 1080f, 260f) -> clicked("qr_pick_image") { activity.toast("Chọn ảnh mã QR") }
        }
    }

    private fun tapTerms(x: Float, y: Float) {
        when {
            inBox(x, y, 0f, 100f, 140f, 260f) -> clicked("back") { activity.goBack(Screen.ACCOUNT) }
            inBox(x, y, 760f, 220f, 1060f, 340f) -> clicked("terms_toc") { activity.showOverlay(Overlay.TERMS_TOC) }
            inBox(x, y, 20f, 2160f, 1060f, 2360f) -> clicked("terms_agree") { activity.showMainTab(Screen.ACCOUNT) }
        }
    }

    private fun handleOverlayTap(x: Float, y: Float) {
        when (overlay) {
            Overlay.LOGIN_REQUIRED -> when {
                inBox(x, y, 850f, 850f, 980f, 990f) -> clicked("login_required_close") { activity.dismissOverlay() }
                inBox(x, y, 120f, 1080f, 960f, 1235f) -> clicked("login_required_ok") { activity.showScreen(Screen.LOGIN) }
                !inBox(x, y, 90f, 840f, 990f, 1260f) -> activity.dismissOverlay()
            }
            Overlay.LANGUAGE -> when {
                inBox(x, y, 170f, 1040f, 910f, 1225f) -> clicked("language_vietnamese") { activity.dismissOverlay(); activity.toast("Tiếng Việt") }
                inBox(x, y, 170f, 1225f, 910f, 1410f) -> clicked("language_english") { activity.dismissOverlay(); activity.toast("English") }
                else -> activity.dismissOverlay()
            }
            Overlay.BOOKING_CHOICE -> when {
                inBox(x, y, 930f, 830f, 1040f, 960f) -> clicked("booking_choice_close") { activity.dismissOverlay() }
                inBox(x, y, 50f, 1010f, 1030f, 1290f) -> clicked("booking_visual") { activity.showScreen(Screen.BOOKING_SCHEDULE) }
                inBox(x, y, 50f, 1290f, 1030f, 1570f) -> clicked("booking_social") { activity.showScreen(Screen.EVENT_DETAIL) }
                else -> activity.dismissOverlay()
            }
            Overlay.DATE_PICKER -> when {
                inBox(x, y, 630f, 1410f, 960f, 1560f) -> clicked("date_apply") { activity.dismissOverlay() }
                !inBox(x, y, 80f, 620f, 1000f, 1600f) -> activity.dismissOverlay()
            }
            Overlay.TERMS_TOC -> when {
                inBox(x, y, 430f, 130f, 1080f, 2340f) -> clicked("terms_toc_item") { activity.dismissOverlay() }
                else -> activity.dismissOverlay()
            }
            Overlay.NONE -> Unit
        }
    }
}
