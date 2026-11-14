package com.ontimepickup.app

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * Pleos Connect UI Design Guide의 토큰을 코드로 옮긴 것.
 * 색: Foundations > Colors (System Color), 글자: Foundations > Typography (Asta Sans),
 * 그림자: Foundations > Shadow, 눌림: Foundations > Interaction (95% 축소 + 배경색 변경).
 */
object Pleos {

    class Palette(
        val canvas: Int,
        val card: Int,
        val control: Int,
        val controlPressed: Int,
        val selected: Int,
        val outline: Int,
        val text: Int,
        val textSecondary: Int,
        val textTertiary: Int,
        val textDisabled: Int,
        val inverse: Int,
        val inversePressed: Int,
        val inverseText: Int,
        val active: Int,
        val positive: Int,
        val negative: Int,
        val cooking: Int,
        val favorite: Int,
        val gleoFrom: Int,
        val gleoTo: Int,
        val gleoText: Int,
        val toast: Int,
        // OnTime Pickup 브랜드 (assets/brand): 네이비 + 민트. 진행·주요 버튼에만 쓴다.
        val brand: Int,
        val brandFill: Int,
        val brandFillPressed: Int,
        val brandOnFill: Int,
    )

    private val dark = Palette(
        canvas = color("#131417"),
        card = color("#1D1E21"),
        control = color("#313236"),
        controlPressed = color("#44464E"),
        selected = color("#44464E"),
        outline = color("#14FFFFFF"),
        text = color("#FFFFFF"),
        textSecondary = color("#D6FFFFFF"),
        textTertiary = color("#A3FFFFFF"),
        textDisabled = color("#52FFFFFF"),
        inverse = color("#EDEEF2"),
        inversePressed = color("#C7C9CE"),
        inverseText = color("#131417"),
        active = color("#00E074"),
        positive = color("#0082FF"),
        negative = color("#FF4C28"),
        cooking = color("#FF8A00"),
        favorite = color("#FFC224"),
        gleoFrom = color("#3E4976"),
        gleoTo = color("#545F93"),
        gleoText = color("#FFFFFF"),
        toast = color("#2B2C30"),
        brand = color("#3CE0B4"),
        brandFill = color("#3CE0B4"),
        brandFillPressed = color("#2CC49C"),
        brandOnFill = color("#0E1A2B"),
    )

    private val light = Palette(
        canvas = color("#F7F8FA"),
        card = color("#FFFFFF"),
        control = color("#EDEEF2"),
        controlPressed = color("#E0E1E6"),
        selected = color("#FFFFFF"),
        outline = color("#14131417"),
        text = color("#131417"),
        textSecondary = color("#D6131417"),
        textTertiary = color("#A3131417"),
        textDisabled = color("#52131417"),
        inverse = color("#313236"),
        inversePressed = color("#44464E"),
        inverseText = color("#FFFFFF"),
        active = color("#02C265"),
        positive = color("#0064FF"),
        negative = color("#FE3D16"),
        cooking = color("#FF8A00"),
        favorite = color("#FFB000"),
        gleoFrom = color("#8896D3"),
        gleoTo = color("#9BA8E2"),
        gleoText = color("#131417"),
        toast = color("#313236"),
        brand = color("#12A882"),
        brandFill = color("#0E1A2B"),
        brandFillPressed = color("#1E3350"),
        brandOnFill = color("#FFFFFF"),
    )

    enum class Type(val size: Float, val weight: Int) {
        HeadlineL(60f, 800),
        HeadlineS(48f, 800),
        TitleM(36f, 700),
        TitleS(32f, 700),
        BodyLStrong(30f, 700),
        BodyMStrong(28f, 700),
        BodyM(28f, 400),
        BodySStrong(26f, 700),
        BodyS(26f, 400),
        LabelMStrong(24f, 700),
        LabelM(24f, 400),
        LabelS(20f, 400),
    }

    private val faces = HashMap<Int, Typeface>()

    fun palette(context: Context): Palette {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_NO) light else dark
    }

    fun typeface(context: Context, weight: Int): Typeface = faces.getOrPut(weight) {
        runCatching {
            Typeface.Builder(context.assets, "fonts/AstaSans.ttf")
                .setFontVariationSettings("'wght' $weight")
                .build()
        }.getOrElse { Typeface.create(Typeface.DEFAULT, weight, false) }
    }

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    fun rounded(context: Context, fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radius)
        if (stroke != null) setStroke(dp(context, 1.5f).toInt(), stroke)
    }

    fun gradient(context: Context, radius: Float, vararg colors: Int, orientation: GradientDrawable.Orientation) =
        GradientDrawable(orientation, colors).apply { cornerRadius = dp(context, radius) }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun tint(color: Int): ColorStateList = ColorStateList.valueOf(color)

    /** 투명도 없이 미리 섞은 색. 그라데이션이 바탕색과 어긋나지 않게 한다. */
    fun blend(top: Int, base: Int, amount: Float): Int {
        fun mix(a: Int, b: Int) = (a * amount + b * (1 - amount)).toInt()
        return Color.rgb(mix(Color.red(top), Color.red(base)), mix(Color.green(top), Color.green(base)), mix(Color.blue(top), Color.blue(base)))
    }

    internal fun color(hex: String) = Color.parseColor(hex)
}

fun TextView.pleos(type: Pleos.Type, color: Int) {
    typeface = Pleos.typeface(context, type.weight)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, type.size)
    setTextColor(color)
    includeFontPadding = false
    setLineSpacing(0f, 1.18f)
}


/** Level 2 그림자(Y 8, Blur 32, 16%) 근사. 다크 테마에서는 테두리로 층을 구분한다. */
fun View.elevate(level: Int) {
    elevation = Pleos.dp(context, if (level >= 3) 20f else 8f)
    outlineSpotShadowColor = Pleos.color(if (level >= 3) "#3D000000" else "#29000000")
    outlineAmbientShadowColor = Pleos.color("#14000000")
}

/** 모든 터치 요소: 누르는 동안 95%로 줄고 배경이 한 단계 진해진다. 손가락이 영역을 벗어나면 클릭은 취소된다. */
fun View.pressable(normal: Int, pressed: Int) {
    setOnTouchListener { view, event ->
        val bg = view.background as? GradientDrawable
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                bg?.setColor(pressed)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                bg?.setColor(normal)
            }
        }
        false
    }
}

