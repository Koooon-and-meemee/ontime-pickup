package com.ontimepickup.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ontimepickup.app.Pleos.Type
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * 주차 중 화면. Pleos Connect 디자인 가이드(Page Navigation · Basic Button · Tabs · Toast)를 따른다.
 * 왼쪽은 지금 주문 상태 하나만 크게(1~2초 안에 읽히게), 오른쪽은 경로 주변 매장.
 * DO(Distraction Optimization)를 선언하지 않았으므로 주행이 시작되면 시스템이 이 화면을 가리고,
 * 주행 중 주문은 Gleo 음성(GleoReceiver)으로만 받는다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var p: Pleos.Palette
    private lateinit var hero: LinearLayout
    private lateinit var etaChip: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var storeList: LinearLayout
    private lateinit var toast: TextView
    private var wide = true
    private var stores: List<Store> = emptyList()
    private var category: String? = null
    private var pending = false
    private var state: PickupService.OrderState = PickupService.OrderState.Idle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Pleos.palette(this)
        wide = resources.configuration.screenWidthDp >= WIDE_DP

        val root = FrameLayout(this).apply { setBackgroundColor(p.canvas) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(28), dp(40), dp(32))
        }
        page.addView(navigationBar(), match())

        hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(36), dp(40), dp(36))
            elevate(2)
        }
        storeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (wide) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(hero, LinearLayout.LayoutParams(0, MATCH, 1f))
            body.addView(storesPane(scroll = true), LinearLayout.LayoutParams(0, MATCH, 1.2f).apply { marginStart = dp(32) })
            page.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
            root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            page.addView(hero, match())
            page.addView(storesPane(scroll = false), match(top = 32))
            root.addView(ScrollView(this).apply { addView(page) }, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        toast = TextView(this).apply {
            pleos(Type.BodySStrong, Color.WHITE)
            background = Pleos.rounded(this@MainActivity, p.toast, 20f)
            setPadding(dp(36), dp(22), dp(36), dp(22))
            maxWidth = dp(792)
            gravity = Gravity.CENTER
            visibility = View.GONE
            elevate(3)
        }
        root.addView(toast, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(24)
        })
        setContentView(root)

        renderHero()
        lifecycleScope.launch {
            PickupService.state.collect {
                state = it
                if (it !is PickupService.OrderState.Idle) pending = false
                renderHero()
                renderEtaChip()
            }
        }
        lifecycleScope.launch { loadStores() }
    }

    // ── Page Navigation ─────────────────────────────────────────────

    private fun navigationBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(28))
        addView(appIcon(72), LinearLayout.LayoutParams(dp(72), dp(72)))
        val titles = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("온타임 픽업", Type.TitleM, p.text, 1))
            addView(text("도착 시간에 맞춰 조리되는 포장 주문", Type.LabelM, p.textTertiary, 1), match(top = 4))
        }
        addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(24) })
        etaChip = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        addView(etaChip, LinearLayout.LayoutParams(WRAP, dp(60)))
    }

    private fun appIcon(size: Int) = ImageView(this).apply {
        setImageResource(R.drawable.ic_brand)
        contentDescription = "온타임 픽업"
        elevate(2)
    }

    private fun renderEtaChip() {
        etaChip.removeAllViews()
        val active = state as? PickupService.OrderState.Active
        if (active == null) {
            etaChip.visibility = View.GONE
            return
        }
        val color = if (active.estimated) p.textTertiary else p.active
        etaChip.background = Pleos.rounded(this, Pleos.withAlpha(color, 0.16f), 30f)
        etaChip.setPadding(dp(22), 0, dp(28), 0)
        etaChip.addView(
            icon(if (active.estimated) R.drawable.ic_schedule else R.drawable.ic_navigation, color),
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) },
        )
        etaChip.addView(text(if (active.estimated) "추정 도착 시간" else "내비 도착 시간 연동", Type.LabelMStrong, color, 1))
        etaChip.visibility = View.VISIBLE
    }

    // ── 지금 주문 (Hero) ────────────────────────────────────────────

    private fun renderHero() {
        hero.removeAllViews()
        if (pending && state is PickupService.OrderState.Idle) return pendingHero()
        when (val s = state) {
            PickupService.OrderState.Idle -> idleHero()
            is PickupService.OrderState.Active -> activeHero(s.order)
            is PickupService.OrderState.Arrived -> arrivedHero(s.order)
            is PickupService.OrderState.Error -> errorHero(s.message)
        }
    }

    private fun idleHero() {
        hero.background = heroBackground(null)
        hero.addView(text("주행 중엔 말로 주문해요", Type.LabelMStrong, p.textTertiary, 1))
        hero.addView(gleoBubble("“글레오, 늘 먹던 거 포장해줘”"), match(top = 16))
        val fav = Favorites.get(this)
        if (fav == null) {
            hero.addView(text("늘 먹던 거", Type.LabelMStrong, p.textTertiary, 1), match(top = 44))
            hero.addView(emptyFavoriteTile(), match(top = 16))
            pushDown()
            hero.addView(howItWorks(), match())
            return
        }
        val store = stores.firstOrNull { it.id == fav.storeId }
        val item = store?.menu?.firstOrNull { it.id == fav.itemId }
        hero.addView(text("늘 먹던 거", Type.LabelMStrong, p.textTertiary, 1), match(top = 44))
        hero.addView(favoriteTile(store, item, fav), match(top = 16))
        pushDown()
        hero.addView(button("지금 주문하기", R.drawable.ic_takeout, primary = true) { order(fav.storeId, fav.itemId) }, match())
    }

    private fun pendingHero() {
        hero.background = heroBackground(p.positive)
        hero.addView(statusChip("주문 준비 중", p.positive), wrap())
        hero.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = Pleos.tint(p.positive)
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { topMargin = dp(36) })
        hero.addView(text("내비에서 매장까지 남은 시간을 확인하고 있어요", Type.TitleS, p.text, 2), match(top = 28))
        hero.addView(text("확인되면 도착 시간에 맞춰 조리 시작 시각이 정해져요", Type.BodyM, p.textSecondary, 2), match(top = 12))
        pushDown()
        hero.addView(stepper(0, p.positive), match())
    }

    private fun activeHero(o: OrderView) {
        val color = statusColor(o.status)
        hero.background = heroBackground(color)
        hero.addView(statusChip(PickupService.statusKo(o.status), color), wrap())
        hero.addView(text(o.storeName, Type.TitleS, p.text, 1), match(top = 28))
        val eta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        eta.addView(text(minutes(o.secToArrive).toString(), Type.HeadlineL, p.text, 1))
        eta.addView(text("분 뒤 도착", Type.BodyLStrong, p.textSecondary, 1), wrap().apply { marginStart = dp(12) })
        hero.addView(eta, match(top = 4))
        hero.addView(text(cookLine(o), Type.BodyM, p.textSecondary, 2), match(top = 8))
        hero.addView(stepper(stepOf(o.status), color), match(top = 44))
        hero.addView(orderFacts(o), match(top = 40))
        pushDown()
        hero.addView(button("주문 취소", R.drawable.ic_close, primary = false) { PickupService.cancel(this) }, match())
    }

    private fun arrivedHero(o: OrderView) {
        hero.background = heroBackground(p.active)
        hero.addView(statusChip("도착", p.active), wrap())
        hero.addView(icon(R.drawable.ic_check_circle, p.active), LinearLayout.LayoutParams(dp(88), dp(88)).apply { topMargin = dp(32) })
        hero.addView(text("매장에 도착을 알렸어요", Type.HeadlineS, p.text, 2), match(top = 20))
        hero.addView(text("${o.storeName} 직원이 차로 가져다 드려요", Type.BodyM, p.textSecondary, 2), match(top = 12))
        hero.addView(stepper(3, p.active), match(top = 44))
        pushDown()
        hero.addView(button("확인", null, primary = true) { PickupService.reset() }, match())
    }

    private fun errorHero(message: String) {
        hero.background = heroBackground(p.negative)
        hero.addView(statusChip("주문 실패", p.negative), wrap())
        hero.addView(text(message, Type.TitleS, p.text, 2), match(top = 28))
        hero.addView(text("잠시 후 다시 주문해 주세요", Type.BodyM, p.textSecondary, 1), match(top = 12))
        pushDown()
        hero.addView(button("확인", null, primary = true) { PickupService.reset() }, match())
    }

    /** 매장 화면에 그대로 넘어간 정보. 운전자가 따로 말할 필요가 없다는 걸 보여 준다. */
    private fun orderFacts(o: OrderView) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Pleos.rounded(this@MainActivity, p.control, 28f)
        setPadding(dp(28), dp(12), dp(28), dp(12))
        listOf(
            R.drawable.ic_car to "차량 ${Favorites.carPlate(this@MainActivity)} · 매장에 전달됨",
            R.drawable.ic_takeout to "사전결제 완료 · ${won(o.total)}",
            R.drawable.ic_navigation to "도착하면 매장에 자동으로 알려요",
        ).forEach { (res, line) ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
            }
            row.addView(icon(res, p.textTertiary), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(20) })
            row.addView(text(line, Type.BodyS, p.textSecondary, 1), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(row, match())
        }
    }

    private fun heroBackground(glow: Int?): GradientDrawable {
        if (glow == null) return Pleos.rounded(this, p.card, 36f, p.outline)
        return Pleos.gradient(
            this, 36f, Pleos.blend(glow, p.card, 0.20f), p.card, p.card,
            orientation = GradientDrawable.Orientation.TOP_BOTTOM,
        ).apply { setStroke(dp(1.5f), Pleos.withAlpha(glow, 0.32f)) }
    }

    private fun pushDown() {
        if (wide) hero.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))
        else hero.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(40)))
    }

    private fun gleoBubble(phrase: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Pleos.gradient(
            this@MainActivity, 32f, p.gleoFrom, p.gleoTo,
            orientation = GradientDrawable.Orientation.LEFT_RIGHT,
        )
        setPadding(dp(24), dp(24), dp(32), dp(24))
        val mic = FrameLayout(this@MainActivity).apply {
            background = Pleos.rounded(this@MainActivity, Pleos.withAlpha(Color.WHITE, 0.16f), 32f)
            addView(icon(R.drawable.ic_mic, p.gleoText), FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        }
        addView(mic, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(24) })
        addView(text(phrase, Type.BodyLStrong, p.gleoText, 2), LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun favoriteTile(store: Store?, item: MenuItem?, fav: Favorites.Favorite) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Pleos.rounded(this@MainActivity, p.control, 28f)
        setPadding(dp(24), dp(24), dp(28), dp(24))
        addView(categoryBadge(store?.category ?: fav.category, 72), LinearLayout.LayoutParams(dp(72), dp(72)))
        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(store?.name ?: fav.label, Type.TitleS, p.text, 1))
        if (item != null) col.addView(text("${item.name} · ${won(item.price)}", Type.BodyS, p.textTertiary, 1), match(top = 6))
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(24) })
    }

    private fun emptyFavoriteTile() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = Pleos.dp(this@MainActivity, 28f)
            setStroke(dp(2), p.textDisabled, Pleos.dp(this@MainActivity, 10f), Pleos.dp(this@MainActivity, 8f))
        }
        setPadding(dp(24), dp(24), dp(28), dp(24))
        val badge = FrameLayout(this@MainActivity).apply {
            background = Pleos.rounded(this@MainActivity, Pleos.blend(p.favorite, p.card, 0.16f), 22f)
            addView(icon(R.drawable.ic_star, p.favorite), FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        }
        addView(badge, LinearLayout.LayoutParams(dp(72), dp(72)))
        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text("아직 정하지 않았어요", Type.TitleS, p.text, 1))
        col.addView(text("메뉴 옆 ☆를 누르면 말로 바로 주문돼요", Type.BodyS, p.textTertiary, 2), match(top = 6))
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(24) })
    }

    private fun howItWorks() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf(
            Triple(R.drawable.ic_mic, "말로 주문", "폰 없이"),
            Triple(R.drawable.ic_timer, "도착 맞춰 조리", "막히면 자동 조정"),
            Triple(R.drawable.ic_car, "차 앞 픽업", "도착하면 알림"),
        ).forEachIndexed { i, (res, title, sub) ->
            val cell = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            val tile = FrameLayout(this@MainActivity).apply {
                background = Pleos.rounded(this@MainActivity, p.control, 20f)
                addView(icon(res, p.textSecondary), FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            }
            cell.addView(tile, LinearLayout.LayoutParams(dp(60), dp(60)))
            cell.addView(text(title, Type.BodySStrong, p.text, 1), match(top = 16))
            cell.addView(text(sub, Type.LabelM, p.textTertiary, 2), match(top = 4))
            addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(20) })
        }
    }

    private fun statusChip(label: String, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Pleos.rounded(this@MainActivity, Pleos.withAlpha(color, 0.16f), 26f)
        setPadding(dp(20), dp(12), dp(24), dp(12))
        addView(View(this@MainActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(12) })
        addView(text(label, Type.LabelMStrong, color, 1))
    }

    /** 주문 → 조리 → 준비 완료 → 도착. 지금 단계만 크게, 지난 단계는 상태색으로 잇는다. */
    private fun stepper(current: Int, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val labels = listOf("주문", "조리", "준비 완료", "도착")
        labels.forEachIndexed { i, label ->
            val done = i <= current
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val track = FrameLayout(this@MainActivity)
            val lines = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val left = when {
                i == 0 -> Color.TRANSPARENT
                done -> color
                else -> p.control
            }
            val right = when {
                i == labels.lastIndex -> Color.TRANSPARENT
                i < current -> color
                else -> p.control
            }
            lines.addView(View(this@MainActivity).apply { setBackgroundColor(left) }, LinearLayout.LayoutParams(0, dp(4), 1f))
            lines.addView(View(this@MainActivity).apply { setBackgroundColor(right) }, LinearLayout.LayoutParams(0, dp(4), 1f))
            track.addView(lines, FrameLayout.LayoutParams(MATCH, dp(4), Gravity.CENTER_VERTICAL))
            val size = if (i == current) 32 else 18
            track.addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (done) color else p.control)
                    if (i == current) setStroke(dp(7), Pleos.blend(color, p.card, 0.35f))
                }
            }, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
            cell.addView(track, LinearLayout.LayoutParams(MATCH, dp(32)))
            cell.addView(
                text(label, if (i == current) Type.LabelMStrong else Type.LabelM, if (done) p.text else p.textTertiary, 1),
                wrap(top = 12),
            )
            addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    // ── 매장 (Tabs + 카드) ───────────────────────────────────────────

    private fun storesPane(scroll: Boolean): View {
        val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(text("경로 주변 포장 매장", Type.TitleS, p.text, 1), match())
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Pleos.rounded(this@MainActivity, p.card, 26f, p.outline)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        pane.addView(tabs, match(top = 20))
        if (scroll) {
            pane.addView(ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(storeList)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(20) })
        } else {
            pane.addView(storeList, match(top = 20))
        }
        return pane
    }

    private suspend fun loadStores() {
        storeList.removeAllViews()
        storeList.addView(text("매장을 불러오는 중…", Type.BodyM, p.textTertiary, 1), match(top = 8))
        stores = runCatching { Api.stores() }.getOrElse {
            storeList.removeAllViews()
            storeList.addView(text("매장 목록을 불러오지 못했어요", Type.BodyMStrong, p.text, 1), match(top = 8))
            storeList.addView(text("네트워크 연결을 확인한 뒤 다시 시도해 주세요", Type.BodyS, p.textTertiary, 2), match(top = 8))
            storeList.addView(button("다시 시도", null, primary = false) { lifecycleScope.launch { loadStores() } }, match(top = 24))
            return
        }
        renderTabs()
        renderStores()
        renderHero()
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        val categories = listOf<String?>(null) + stores.map { it.category }.distinct()
        categories.forEach { cat ->
            val selected = cat == category
            val item = FrameLayout(this).apply {
                background = Pleos.rounded(this@MainActivity, if (selected) p.selected else Color.TRANSPARENT, 20f)
                isClickable = true
                contentDescription = cat ?: "전체"
                setOnClickListener {
                    category = cat
                    renderTabs()
                    renderStores()
                }
                if (selected) elevate(1) else pressable(Color.TRANSPARENT, p.control)
            }
            item.addView(
                text(cat ?: "전체", Type.BodyMStrong, if (selected) p.text else p.textTertiary, 1),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER),
            )
            tabs.addView(item, LinearLayout.LayoutParams(0, dp(72), 1f))
        }
    }

    private fun renderStores() {
        if (stores.isEmpty()) return
        storeList.removeAllViews()
        val fav = Favorites.get(this)
        stores.filter { category == null || it.category == category }.forEachIndexed { i, store ->
            storeList.addView(storeCard(store, fav), match(top = if (i == 0) 0 else 20))
        }
    }

    private fun storeCard(store: Store, fav: Favorites.Favorite?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Pleos.rounded(this@MainActivity, p.card, 32f, p.outline)
        setPadding(dp(28), dp(28), dp(28), dp(28))
        val head = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(categoryBadge(store.category, 72), LinearLayout.LayoutParams(dp(72), dp(72)))
        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(store.name, Type.TitleS, p.text, 1))
        col.addView(
            text("${store.category} · 조리 ${store.prepMin}분 · ${store.address.substringAfterLast(' ')}", Type.LabelM, p.textTertiary, 1),
            match(top = 6),
        )
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(24) })
        addView(head, match())
        store.menu.forEach { item ->
            val isFavorite = fav != null && fav.storeId == store.id && fav.itemId == item.id
            addView(menuRow(store, item, isFavorite), match(top = 16))
        }
    }

    /** 행 전체가 Basic Button(Large). 오른쪽 별은 따로 눌리는 Icon Button. */
    private fun menuRow(store: Store, item: MenuItem, isFavorite: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Pleos.rounded(this@MainActivity, p.control, 26f)
        minimumHeight = dp(104)
        setPadding(dp(28), dp(16), dp(8), dp(16))
        isClickable = true
        contentDescription = "${item.name} 주문"
        setOnClickListener { order(store.id, item.id) }
        pressable(p.control, p.controlPressed)

        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(item.name, Type.BodyMStrong, p.text, 1))
        col.addView(text(won(item.price), Type.LabelM, p.textTertiary, 1), match(top = 6))
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        val cta = TextView(this@MainActivity).apply {
            text = "주문"
            pleos(Type.LabelMStrong, p.inverseText)
            background = Pleos.rounded(this@MainActivity, p.inverse, 22f)
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        addView(cta, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(16) })

        val star = FrameLayout(this@MainActivity).apply {
            background = Pleos.rounded(this@MainActivity, Color.TRANSPARENT, 24f)
            isClickable = true
            contentDescription = if (isFavorite) "늘 먹던 거 해제" else "늘 먹던 거로 저장"
            setOnClickListener { toggleFavorite(store, item, isFavorite) }
            pressable(Color.TRANSPARENT, p.controlPressed)
        }
        star.addView(
            icon(if (isFavorite) R.drawable.ic_star_fill else R.drawable.ic_star, if (isFavorite) p.favorite else p.textTertiary),
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER),
        )
        addView(star, LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginStart = dp(4) })
    }

    private fun categoryBadge(category: String, size: Int): View {
        val (res, color) = when (category) {
            "치킨" -> R.drawable.ic_cat_chicken to Color.parseColor("#FF8A00")
            "피자" -> R.drawable.ic_cat_pizza to Color.parseColor("#FF4C28")
            "중식" -> R.drawable.ic_cat_chinese to Color.parseColor("#FFC224")
            "국밥" -> R.drawable.ic_cat_soup to Color.parseColor("#00B975")
            else -> R.drawable.ic_store to p.positive
        }
        val glyph = (size * 0.55f).toInt()
        return FrameLayout(this).apply {
            background = Pleos.rounded(this@MainActivity, Pleos.blend(color, p.card, 0.18f), size * 0.3f)
            addView(icon(res, color), FrameLayout.LayoutParams(dp(glyph), dp(glyph), Gravity.CENTER))
        }
    }

    // ── 동작 ─────────────────────────────────────────────────────────

    private fun order(storeId: String, itemId: String) {
        if (state is PickupService.OrderState.Active || pending) {
            showToast("진행 중인 주문이 있어요 · 취소한 뒤 다시 주문해 주세요")
            return
        }
        pending = true
        renderHero()
        PickupService.order(this, storeId, itemId)
        showToast("주문을 보냈어요 · 도착 시간에 맞춰 조리를 시작해요")
    }

    private fun toggleFavorite(store: Store, item: MenuItem, wasFavorite: Boolean) {
        if (wasFavorite) {
            Favorites.clear(this)
            showToast("늘 먹던 거를 해제했어요")
        } else {
            Favorites.save(this, store, item)
            showToast("늘 먹던 거로 저장했어요 · ${store.name} ${item.name}")
        }
        renderStores()
        renderHero()
    }

    private fun showToast(message: String) {
        toast.text = message
        toast.animate().cancel()
        toast.alpha = 0f
        toast.translationY = dp(16).toFloat()
        toast.visibility = View.VISIBLE
        toast.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(180).withEndAction {
            toast.animate().alpha(0f).setStartDelay(2400).setDuration(220).withEndAction {
                toast.visibility = View.GONE
            }.start()
        }.start()
    }

    // ── 표시 규칙 ────────────────────────────────────────────────────

    private fun cookLine(o: OrderView) = when (o.status) {
        "SCHEDULED" -> if (o.secToCookStart >= 60) "${minutes(o.secToCookStart)}분 뒤 조리 시작 · 늦어지면 함께 늦춰져요" else "곧 조리를 시작해요"
        "COOKING" -> "조리 중 · 도착 직전에 완성돼요"
        "READY" -> "준비 완료 · 도착하면 바로 받아요"
        else -> PickupService.statusKo(o.status)
    }

    private fun statusColor(status: String) = when (status) {
        "SCHEDULED" -> p.positive
        "COOKING" -> p.cooking
        "CANCELLED" -> p.textTertiary
        else -> p.active
    }

    private fun stepOf(status: String) = when (status) {
        "SCHEDULED" -> 0
        "COOKING" -> 1
        "READY" -> 2
        else -> 3
    }

    private fun minutes(sec: Int) = maxOf(1, ceil(sec / 60.0).toInt())

    private fun won(price: Int) = "%,d원".format(price)

    // ── 뷰 도우미 ────────────────────────────────────────────────────

    private fun text(s: CharSequence, type: Type, color: Int, maxLines: Int = 3) = TextView(this).apply {
        text = s
        pleos(type, color)
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun icon(res: Int, color: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = Pleos.tint(color)
    }

    private fun button(label: String, iconRes: Int?, primary: Boolean, onClick: () -> Unit): View {
        val normal = if (primary) p.inverse else p.control
        val pressed = if (primary) p.inversePressed else p.controlPressed
        val fg = if (primary) p.inverseText else p.text
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Pleos.rounded(this@MainActivity, normal, 28f)
            minimumHeight = dp(100)
            setPadding(dp(40), 0, dp(40), 0)
            if (iconRes != null) {
                addView(icon(iconRes, fg), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
            }
            addView(text(label, Type.BodyLStrong, fg, 1))
            isClickable = true
            contentDescription = label
            setOnClickListener { onClick() }
            pressable(normal, pressed)
        }
    }

    private fun match(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(top) }

    private fun dp(value: Int) = Pleos.dp(this, value.toFloat()).toInt()

    private fun dp(value: Float) = Pleos.dp(this, value).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val WIDE_DP = 1200
    }
}
