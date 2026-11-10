package com.zenoue.ccbridge.feature

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class Palette(ctx: Context) {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val bg = if (dark) 0xFF0F1412.toInt() else 0xFFF3F6F4.toInt()
    val card = if (dark) 0xFF1A211E.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFE7EDEA.toInt() else 0xFF15201C.toInt()
    val muted = if (dark) 0xFF93A29C.toInt() else 0xFF5B6A64.toInt()
    val accent = if (dark) 0xFF66D6B4.toInt() else 0xFF17795B.toInt()
    val onAccent = if (dark) 0xFF06211A.toInt() else 0xFFFFFFFF.toInt()
    val warn = if (dark) 0xFFF2B35A.toInt() else 0xFFA85B00.toInt()
    val bad = if (dark) 0xFFFF8A80.toInt() else 0xFFC62828.toInt()
    val field = if (dark) 0xFF101714.toInt() else 0xFFEFF3F1.toInt()
    val line = if (dark) 0xFF2A322E.toInt() else 0xFFDFE6E2.toInt()
    val series2 = if (dark) 0xFF8AB4F8.toInt() else 0xFF2F5FB3.toInt()
    val term = if (dark) 0xFF0A0E0C.toInt() else 0xFFFAFCFB.toInt()
}

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

class UiKit(val ctx: Context, val p: Palette) {

    fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun card(): LinearLayout = column().apply {
        background = GradientDrawable().apply { setColor(p.card); cornerRadius = ctx.dp(14).toFloat() }
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dp(12) }
    }

    fun title(s: String) = TextView(ctx).apply {
        text = s; setTextColor(p.text); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, ctx.dp(8))
    }

    fun body(s: String, color: Int = p.text, size: Float = 14f) = TextView(ctx).apply {
        text = s; setTextColor(color); textSize = size; setLineSpacing(0f, 1.15f)
    }

    fun muted(s: String) = body(s, p.muted, 13f)

    fun mono(s: String) = TextView(ctx).apply {
        text = s; setTextColor(p.text); textSize = 11.5f; typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    fun field(hint: String, value: String, password: Boolean = false, numeric: Boolean = false): EditText = EditText(ctx).apply {
        setText(value); this.hint = hint
        setTextColor(p.text); setHintTextColor(p.muted); textSize = 14f
        background = GradientDrawable().apply { setColor(p.field); cornerRadius = ctx.dp(10).toFloat() }
        setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6) }
    }

    fun label(s: String) = TextView(ctx).apply {
        text = s; setTextColor(p.muted); textSize = 12.5f; setPadding(0, ctx.dp(8), 0, 0)
    }

    fun button(s: String, primary: Boolean = true, onClick: () -> Unit) = Button(ctx).apply {
        text = s; isAllCaps = false; textSize = 14f
        setTextColor(if (primary) p.onAccent else p.accent)
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(22).toFloat()
            if (primary) setColor(p.accent) else { setColor(Color.TRANSPARENT); setStroke(ctx.dp(1), p.accent) }
        }
        minimumHeight = ctx.dp(46)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) }
    }

    fun kv(k: String, v: String, vc: Int = p.text) = row().apply {
        setPadding(0, ctx.dp(4), 0, ctx.dp(4))
        addView(body(k, p.muted), LinearLayout.LayoutParams(0, -2, 1f))
        addView(body(v, vc).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END })
    }

    fun divider() = View(ctx).apply {
        setBackgroundColor(p.line)
        layoutParams = LinearLayout.LayoutParams(-1, ctx.dp(1)).apply { topMargin = ctx.dp(8); bottomMargin = ctx.dp(4) }
    }
}
