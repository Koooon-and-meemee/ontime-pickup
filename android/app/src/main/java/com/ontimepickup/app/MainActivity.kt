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
import androidx.core.view.doOnPreDraw
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
    private var storeScroll: ScrollView? = null
    private val parallax = ArrayList<Pair<View, ImageView>>()
    private var animateStores = true
    private var heroKey: String? = null
    private var heroFresh: List<View> = emptyList()
    private var popStar: String? = null
    private lateinit var listPane: View
    private lateinit var detailPane: LinearLayout
    private lateinit var detailBody: LinearLayout
    private var detailScroll: ScrollView? = null
    private var openStoreId: String? = null
    private var state: PickupService.OrderState = PickupService.OrderState.Idle
    private val ringStart = HashMap<String, Int>()
    private val ringShown = HashMap<String, Float>()
    private val cart = LinkedHashMap<String, Int>()
    private var cartStoreId: String? = null

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
        val color = if (active.estimated) p.textTertiary else p.brand
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
        heroFresh = emptyList()
        val key = when (val s = state) {
            PickupService.OrderState.Idle -> if (pending) "pending" else "idle:${cart.isEmpty()}:${Favorites.get(this)?.itemId}"
            is PickupService.OrderState.Active -> "active"
            is PickupService.OrderState.Arrived -> "arrived"
            is PickupService.OrderState.Error -> "error"
        }
        if (pending && state is PickupService.OrderState.Idle) pendingHero()
        else when (val s = state) {
            PickupService.OrderState.Idle -> idleHero()
            is PickupService.OrderState.Active -> activeHero(s.order)
            is PickupService.OrderState.Arrived -> arrivedHero(s.order)
            is PickupService.OrderState.Error -> errorHero(s.message)
        }
        val prev = heroKey
        heroKey = key
        if (prev == key || stores.isEmpty()) return
        // 같은 대기 화면 안에서의 변화(장바구니 등장 등)는 바뀐 부분만, 상태가 바뀌면 전체를 위에서부터 차례로.
        val targets = if (prev?.startsWith("idle") == true && key.startsWith("idle") && heroFresh.isNotEmpty()) heroFresh
        else (0 until hero.childCount).map { hero.getChildAt(it) }
        Motion.rise(targets, distanceDp = 16f, stagger = 45, duration = 480)
    }

    private fun idleHero() {
        hero.background = heroBackground(null)
        hero.addView(text("주행 중에는 음성 주문만 가능합니다", Type.LabelMStrong, p.textTertiary, 1))
        hero.addView(gleoBubble("“글레오, 늘 먹던 거 포장해줘”"), match(top = 16))
        val fav = Favorites.get(this)
        hero.addView(text("즐겨찾기", Type.LabelMStrong, p.textTertiary, 1), match(top = 44))
        if (fav == null) {
            hero.addView(emptyFavoriteTile(), match(top = 16))
        } else {
            val store = stores.firstOrNull { it.id == fav.storeId }
            val item = store?.menu?.firstOrNull { it.id == fav.itemId }
            hero.addView(favoriteTile(store, item, fav), match(top = 16))
        }

        val cartStore = stores.firstOrNull { it.id == cartStoreId }
        if (cartStore != null && cart.isNotEmpty()) {
            val section = cartSection(cartStore)
            hero.addView(section, match(top = 40))
            pushDown()
            val count = cart.values.sum()
            val cta = button("포장 주문하기 · ${count}개 · ${won(cartTotal(cartStore))}", R.drawable.ic_takeout, primary = true) { orderCart(cartStore) }
            hero.addView(cta, match())
            heroFresh = listOf(section, cta)
            return
        }
        pushDown()
        val bottom = if (fav == null) howItWorks()
        else button("즐겨찾기 바로 주문", R.drawable.ic_takeout, primary = true) { order(fav.storeId, listOf(fav.itemId)) }
        hero.addView(bottom, match())
        heroFresh = listOf(hero.getChildAt(3), bottom)
    }

    /** 장바구니: 한 매장 메뉴만 담는다. 줄마다 수량 조절, 아래에 합계. */
    private fun cartSection(store: Store) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text("장바구니 · ${store.name}", Type.LabelMStrong, p.textTertiary, 1), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(TextView(this@MainActivity).apply {
            text = "비우기"
            pleos(Type.LabelMStrong, p.textTertiary)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = Pleos.rounded(this@MainActivity, Color.TRANSPARENT, 20f)
            isClickable = true
            setOnClickListener { clearCart(); refreshMenus(); renderHero() }
            pressable(Color.TRANSPARENT, p.controlPressed)
        })
        addView(head, match())

        val box = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = Pleos.rounded(this@MainActivity, p.control, 28f)
            setPadding(dp(28), dp(8), dp(16), dp(8))
        }
        cart.forEach { (itemId, qty) ->
            val item = store.menu.firstOrNull { it.id == itemId } ?: return@forEach
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(96)
            }
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(item.name, Type.BodyMStrong, p.text, 1))
            col.addView(text(won(item.price * qty), Type.LabelM, p.textTertiary, 1), match(top = 4))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(qtyStepper(qty, { changeQty(store, item, -1) }, { changeQty(store, item, +1) }))
            box.addView(row, match())
        }
        val total = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
        }
        total.addView(text("합계 · 사전결제", Type.BodyS, p.textSecondary, 1), LinearLayout.LayoutParams(0, WRAP, 1f))
        total.addView(text(won(cartTotal(store)), Type.BodyLStrong, p.text, 1), wrap().apply { marginEnd = dp(12) })
        box.addView(View(this@MainActivity).apply { setBackgroundColor(p.outline) }, LinearLayout.LayoutParams(MATCH, dp(1)).apply { marginEnd = dp(12) })
        box.addView(total, match())
        addView(box, match(top = 8))
    }

    /** − n + . 차량 화면이라 버튼은 64dp 이상. */
    private fun qtyStepper(qty: Int, onMinus: () -> Unit, onPlus: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Pleos.rounded(this@MainActivity, p.card, 24f, p.outline)
        fun step(label: String, desc: String, action: () -> Unit) = FrameLayout(this@MainActivity).apply {
            background = Pleos.rounded(this@MainActivity, Color.TRANSPARENT, 24f)
            isClickable = true
            contentDescription = desc
            setOnClickListener { action() }
            pressable(Color.TRANSPARENT, p.controlPressed)
            addView(text(label, Type.BodyLStrong, p.text, 1), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
        addView(step("-", "수량 줄이기", onMinus), LinearLayout.LayoutParams(dp(68), dp(68)))
        addView(text(qty.toString(), Type.BodyMStrong, p.text, 1).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(44), WRAP))
        addView(step("+", "수량 늘리기", onPlus), LinearLayout.LayoutParams(dp(68), dp(68)))
    }

    private fun pendingHero() {
        hero.background = heroBackground(p.brand)
        hero.addView(statusChip("주문 준비 중", p.brand), wrap())
        hero.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = Pleos.tint(p.brand)
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { topMargin = dp(36) })
        hero.addView(text("내비에서 매장까지 남은 시간 확인 중", Type.TitleS, p.text, 2), match(top = 28))
        hero.addView(text("확인되는 즉시 도착 시간 기준으로 조리 시작 시각 확정", Type.BodyM, p.textSecondary, 2), match(top = 12))
        pushDown()
        hero.addView(stepper(0, p.brand), match())
    }

    private fun activeHero(o: OrderView) {
        val color = statusColor(o.status)
        hero.background = heroBackground(color)
        hero.addView(statusChip(PickupService.statusKo(o.status), color), wrap())
        // 브랜드 로고와 같은 모양: 줄어드는 링 = 매장까지 남은 시간, 링 끝의 점 = 다가오는 차
        val start = ringStart.getOrPut(o.id) { o.secToArrive }.let { maxOf(it, o.secToArrive) }
        ringStart[o.id] = start
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ringBox = FrameLayout(this)
        ringBox.addView(CountdownRing(this, p.control, p.brand, p.text).apply {
            setFraction(if (start > 0) o.secToArrive.toFloat() / start else 0f, ringShown[o.id])
            ringShown[o.id] = if (start > 0) o.secToArrive.toFloat() / start else 0f
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        center.addView(text(minutes(o.secToArrive).toString(), Type.HeadlineL, p.text, 1), wrap())
        center.addView(text("분 뒤 도착", Type.LabelMStrong, p.textTertiary, 1), wrap(top = 2))
        ringBox.addView(center, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        row.addView(ringBox, LinearLayout.LayoutParams(dp(196), dp(196)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(o.storeName, Type.TitleS, p.text, 1))
        col.addView(text(cookLine(o), Type.BodyM, p.textSecondary, 3), match(top = 10))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(32) })
        hero.addView(row, match(top = 24))
        hero.addView(stepper(stepOf(o.status), color), match(top = 36))
        hero.addView(orderFacts(o), match(top = 32))
        pushDown()
        hero.addView(button("주문 취소", R.drawable.ic_close, primary = false) { PickupService.cancel(this) }, match())
    }

    private fun arrivedHero(o: OrderView) {
        hero.background = heroBackground(p.brand)
        hero.addView(statusChip("도착", p.brand), wrap())
        hero.addView(icon(R.drawable.ic_check_circle, p.brand), LinearLayout.LayoutParams(dp(88), dp(88)).apply { topMargin = dp(32) })
        hero.addView(text("매장에 도착 알림 완료", Type.HeadlineS, p.text, 2), match(top = 20))
        hero.addView(text("${o.storeName} 직원이 차량으로 전달합니다", Type.BodyM, p.textSecondary, 2), match(top = 12))
        hero.addView(stepper(3, p.brand), match(top = 44))
        pushDown()
        hero.addView(button("확인", null, primary = true) { PickupService.reset() }, match())
    }

    private fun errorHero(message: String) {
        hero.background = heroBackground(p.negative)
        hero.addView(statusChip("주문 실패", p.negative), wrap())
        hero.addView(text(message, Type.TitleS, p.text, 2), match(top = 28))
        hero.addView(text("잠시 후 다시 주문해 주십시오", Type.BodyM, p.textSecondary, 1), match(top = 12))
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
            R.drawable.ic_navigation to "도착 시 매장에 자동 알림",
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
        val photo = storePhoto(fav.storeId)
        if (photo != null) addView(photoThumb(photo, 96, 22f))
        else addView(categoryBadge(store?.category ?: fav.category, 72), LinearLayout.LayoutParams(dp(72), dp(72)))
        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(store?.name ?: fav.label, Type.TitleS, p.text, 1))
        if (item != null) col.addView(text("${item.name} · ${won(item.price)}", Type.BodyS, p.textTertiary, 1), match(top = 6))
        if (store != null && store.rating > 0) col.addView(ratingLine(store), match(top = 8))
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
        col.addView(text("아직 지정되지 않음", Type.TitleS, p.text, 1))
        col.addView(text("메뉴 옆 ☆로 저장 · “늘 먹던 거 포장해줘”로 음성 주문", Type.BodyS, p.textTertiary, 2), match(top = 6))
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
                background = Pleos.rounded(this@MainActivity, Pleos.blend(p.brand, p.card, 0.14f), 20f)
                addView(icon(res, p.brand), FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
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

    /** 오른쪽 영역: 매장 목록 ↔ 매장 상세(메뉴판). 같은 자리에서 옆으로 밀려 바뀐다. */
    private fun storesPane(scroll: Boolean): View {
        val root = FrameLayout(this)
        listPane = listPane(scroll)
        root.addView(listPane, FrameLayout.LayoutParams(MATCH, if (scroll) MATCH else WRAP))
        detailPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val back = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Pleos.rounded(this@MainActivity, p.card, 26f, p.outline)
            setPadding(dp(16), 0, dp(32), 0)
            isClickable = true
            contentDescription = "매장 목록으로"
            setOnClickListener { closeStore() }
            pressable(p.card, p.control)
            addView(icon(R.drawable.ic_chevron, p.text).apply { rotation = 180f }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
            addView(text("매장 목록", Type.BodyMStrong, p.text, 1))
        }
        detailPane.addView(back, LinearLayout.LayoutParams(WRAP, dp(80)))
        detailBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (scroll) {
            val sv = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(detailBody)
            }
            detailScroll = sv
            detailPane.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(20) })
        } else {
            detailPane.addView(detailBody, match(top = 20))
        }
        root.addView(detailPane, FrameLayout.LayoutParams(MATCH, if (scroll) MATCH else WRAP))
        return root
    }

    private fun listPane(scroll: Boolean): View {
        val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pane.addView(text("포장 매장", Type.TitleS, p.text, 1), match())
        pane.addView(
            text("주문 시 경로에 즉시 반영됩니다", Type.LabelM, p.textTertiary, 1),
            match(top = 6),
        )
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Pleos.rounded(this@MainActivity, p.card, 26f, p.outline)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        pane.addView(tabs, match(top = 20))
        if (scroll) {
            val sv = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(storeList)
                setOnScrollChangeListener { _, _, _, _, _ -> updateParallax() }
            }
            storeScroll = sv
            pane.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(20) })
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
            storeList.addView(text("매장 목록을 불러오지 못했습니다", Type.BodyMStrong, p.text, 1), match(top = 8))
            storeList.addView(text("네트워크 연결 확인 후 다시 시도해 주십시오", Type.BodyS, p.textTertiary, 2), match(top = 8))
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
                    if (category == cat) return@setOnClickListener
                    category = cat
                    animateStores = true
                    storeScroll?.scrollTo(0, 0)
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
        parallax.clear()
        val fav = Favorites.get(this)
        val cards = stores.filter { category == null || it.category == category }.mapIndexed { i, store ->
            storeCard(store, fav).also { storeList.addView(it, match(top = if (i == 0) 0 else 20)) }
        }
        if (animateStores) {
            animateStores = false
            Motion.rise(cards, distanceDp = 32f, stagger = 70, duration = 620)
        }
        storeList.doOnPreDraw { updateParallax() }
    }

    /** 스크롤할 때 음식 사진이 카드보다 조금 느리게 움직여 깊이감을 준다. */
    private fun updateParallax() {
        val sv = storeScroll ?: return
        val viewport = sv.height.takeIf { it > 0 } ?: return
        parallax.forEach { (card, img) ->
            val center = card.top + img.height / 2f - sv.scrollY - viewport / 2f
            val limit = img.height * (PHOTO_SCALE - 1f) / 2f
            img.translationY = (-center * PARALLAX).coerceIn(-limit, limit)
        }
    }

    private fun refreshMenus() {
        if (openStoreId != null) renderDetail() else renderStores()
    }

    private fun prepLabel(store: Store) =
        if (store.prepMax > store.prepMin) "조리 ${store.prepMin}–${store.prepMax}분" else "조리 ${store.prepMin}분"

    /**
     * 음식 사진을 위에 크게, 매장 로고가 사진 아래 경계에 걸치고, 그 아래 이름·별점.
     * 목록에서는 대표 메뉴 2개와 "전체 메뉴 보기", 상세에서는 머리 부분만 쓴다.
     */
    private fun storeCard(store: Store, fav: Favorites.Favorite?, detail: Boolean = false) = FrameLayout(this).apply {
        val bannerDp = if (detail) DETAIL_BANNER_DP else BANNER_DP
        background = Pleos.rounded(this@MainActivity, p.card, 32f, p.outline)
        clipToOutline = true
        if (!detail) {
            isClickable = true
            contentDescription = "${store.name} 메뉴 보기"
            setOnClickListener { openStore(store) }
            pressable(p.card, p.card)
        }
        val stack = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(stack, FrameLayout.LayoutParams(MATCH, WRAP))
        // 로고는 사진과 본문 경계에 걸치도록 카드 맨 위 레이어에 얹는다.
        addView(storeLogo(store, LOGO_DP), FrameLayout.LayoutParams(dp(LOGO_DP), dp(LOGO_DP)).apply {
            topMargin = dp(bannerDp - LOGO_DP / 2 + 8)
            marginStart = dp(28)
        })

        val banner = FrameLayout(this@MainActivity)
        val photo = storePhoto(store.id)
        if (photo != null) {
            val img = ImageView(this@MainActivity).apply {
                setImageResource(photo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                scaleX = PHOTO_SCALE
                scaleY = PHOTO_SCALE
                contentDescription = "${store.name} 대표 메뉴 사진"
            }
            banner.clipChildren = true
            banner.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            if (!detail) parallax += this to img
        } else {
            banner.setBackgroundColor(Pleos.blend(p.brand, p.card, 0.14f))
            banner.addView(categoryBadge(store.category, 96), FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        }
        val prep = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Pleos.rounded(this@MainActivity, Pleos.withAlpha(Color.BLACK, 0.62f), 22f)
            setPadding(dp(16), dp(8), dp(20), dp(8))
            addView(icon(R.drawable.ic_timer, Color.WHITE), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) })
            addView(text(prepLabel(store), Type.LabelMStrong, Color.WHITE, 1))
        }
        banner.addView(prep, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(20); marginEnd = dp(20)
        })
        stack.addView(banner, LinearLayout.LayoutParams(MATCH, dp(bannerDp)))

        val body = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(28))
        }
        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(store.name, if (detail) Type.TitleM else Type.TitleS, p.text, 1))
        col.addView(ratingLine(store), match(top = 8))
        body.addView(col, match().apply { marginStart = dp(LOGO_DP + 20) })
        if (detail) {
            body.addView(
                text("포장 전용 · ${prepLabel(store)} · ${store.address}", Type.BodyS, p.textSecondary, 1),
                match(top = 24),
            )
        } else {
            val picks = store.menu.filter { it.popular }.ifEmpty { store.menu }.take(2)
            body.addView(text("대표 메뉴", Type.LabelMStrong, p.textTertiary, 1), match(top = 24))
            picks.forEach { item ->
                val isFavorite = fav != null && fav.storeId == store.id && fav.itemId == item.id
                body.addView(menuRow(store, item, isFavorite), match(top = 12))
            }
            body.addView(moreRow(store), match(top = 12))
        }
        stack.addView(body, match())
    }

    private fun moreRow(store: Store) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = Pleos.rounded(this@MainActivity, p.card, 26f, p.outline)
        minimumHeight = dp(88)
        isClickable = true
        contentDescription = "${store.name} 전체 메뉴 보기"
        setOnClickListener { openStore(store) }
        pressable(p.card, p.control)
        addView(text("전체 메뉴 ${store.menu.size}개 보기", Type.BodyMStrong, p.text, 1))
        addView(icon(R.drawable.ic_chevron, p.textTertiary), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) })
    }

    // ── 매장 상세(메뉴판) ───────────────────────────────────────────

    private fun openStore(store: Store) {
        openStoreId = store.id
        renderDetail()
        detailScroll?.let { sv ->
            sv.scrollTo(0, 0)
            sv.doOnPreDraw { sv.scrollTo(0, 0) }
        }
        listPane.animate().cancel()
        listPane.animate().alpha(0f).translationX(-dp(48).toFloat()).setStartDelay(0).setDuration(220)
            .setInterpolator(Motion.standard).withEndAction { listPane.visibility = View.GONE }.start()
        detailPane.animate().cancel()
        detailPane.visibility = View.VISIBLE
        detailPane.alpha = 0f
        detailPane.translationX = dp(72).toFloat()
        detailPane.animate().alpha(1f).translationX(0f).setStartDelay(60).setDuration(520)
            .setInterpolator(Motion.emphasized).withEndAction(null).start()
        Motion.rise((0 until detailBody.childCount).map { detailBody.getChildAt(it) }.take(4), distanceDp = 20f, stagger = 50, duration = 560)
    }

    private fun closeStore() {
        openStoreId = null
        renderStores()
        detailPane.animate().cancel()
        detailPane.animate().alpha(0f).translationX(dp(72).toFloat()).setStartDelay(0).setDuration(220)
            .setInterpolator(Motion.standard).withEndAction { detailPane.visibility = View.GONE }.start()
        listPane.animate().cancel()
        listPane.visibility = View.VISIBLE
        listPane.animate().alpha(1f).translationX(0f).setStartDelay(60).setDuration(480)
            .setInterpolator(Motion.emphasized).withEndAction(null).start()
    }

    /** 메뉴판: 그룹 바로가기 칩 → 그룹별 메뉴. 수량을 바꿔도 스크롤 위치는 그대로. */
    private fun renderDetail() {
        val store = stores.firstOrNull { it.id == openStoreId } ?: return closeStore()
        val fav = Favorites.get(this)
        detailBody.removeAllViews()
        detailBody.addView(storeCard(store, fav, detail = true), match())

        val groups = store.menu.groupBy { it.group.ifEmpty { "메뉴" } }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        detailBody.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, match(top = 28))

        groups.forEach { (group, items) ->
            val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            head.addView(text(group, Type.TitleS, p.text, 1))
            head.addView(text("  ${items.size}", Type.BodyM, p.textTertiary, 1))
            section.addView(head, match())
            items.forEach { item ->
                val isFavorite = fav != null && fav.storeId == store.id && fav.itemId == item.id
                section.addView(menuRow(store, item, isFavorite), match(top = 14))
            }
            detailBody.addView(section, match(top = 40))

            chips.addView(TextView(this).apply {
                text = group
                pleos(Type.BodySStrong, p.text)
                background = Pleos.rounded(this@MainActivity, p.card, 24f, p.outline)
                setPadding(dp(32), dp(18), dp(32), dp(18))
                isClickable = true
                setOnClickListener {
                    detailScroll?.let { sv ->
                        android.animation.ObjectAnimator.ofInt(sv, "scrollY", sv.scrollY, section.top - dp(8)).apply {
                            duration = 560
                            interpolator = Motion.emphasized
                        }.start()
                    }
                }
                pressable(p.card, p.control)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(12) })
        }
        detailBody.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(40)))
    }

    /** ★ 4.8 (1,204) · 치킨 · 판교 */
    private fun ratingLine(store: Store) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (store.rating > 0) {
            addView(icon(R.drawable.ic_star_fill, p.favorite), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(6) })
            addView(text("%.1f".format(store.rating), Type.LabelMStrong, p.text, 1))
            if (store.reviews > 0) {
                addView(text(" (%,d)".format(store.reviews), Type.LabelM, p.textTertiary, 1))
            }
            addView(text("  ·  ", Type.LabelM, p.textDisabled, 1))
        }
        addView(text("${store.category} · ${store.address.substringAfterLast(' ')}", Type.LabelM, p.textTertiary, 1))
    }

    /** 매장 로고 자리. 실제 로고 대신 매장 고유 색과 두 글자 워드마크로 그린다. 같은 브랜드 지점은 같은 로고. */
    private fun storeLogo(store: Store, size: Int): View {
        val (mark, color) = when (store.id) {
            "s0", "s1" -> "바삭" to 0xFFE8772E.toInt()
            "s2" -> "화덕" to 0xFFC8402F.toInt()
            "s3" -> "손짜장" to 0xFF2B2B33.toInt()
            "s4" -> "순대" to 0xFF8A5A3C.toInt()
            else -> store.name.substringAfterLast(' ').take(2) to p.brand
        }
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(5), p.card)
            }
            elevate(2)
            addView(text(mark, if (mark.length > 2) Type.LabelMStrong else Type.BodyMStrong, Color.WHITE, 1).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
    }

    private fun storePhoto(storeId: String): Int? = when (storeId) {
        "s0" -> R.drawable.store_s0
        "s1" -> R.drawable.store_s1
        "s2" -> R.drawable.store_s2
        "s3" -> R.drawable.store_s3
        "s4" -> R.drawable.store_s4
        else -> null
    }

    private fun photoThumb(res: Int, size: Int, radius: Float) = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Pleos.rounded(this@MainActivity, p.control, radius)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** 행을 누르면 장바구니에 담긴다. 담긴 메뉴는 수량 조절로 바뀐다. 오른쪽 별은 따로 눌리는 Icon Button. */
    private fun menuRow(store: Store, item: MenuItem, isFavorite: Boolean) = LinearLayout(this).apply {
        val qty = if (cartStoreId == store.id) cart[item.id] ?: 0 else 0
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Pleos.rounded(this@MainActivity, p.control, 26f)
        minimumHeight = dp(104)
        setPadding(dp(28), dp(16), dp(8), dp(16))
        isClickable = true
        contentDescription = "${item.name} 장바구니에 담기"
        setOnClickListener { changeQty(store, item, +1) }
        pressable(p.control, p.controlPressed)

        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val nameRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(text(item.name, Type.BodyMStrong, p.text, 1))
        if (item.popular) {
            nameRow.addView(TextView(this@MainActivity).apply {
                text = "인기"
                pleos(Type.LabelS, p.brand)
                background = Pleos.rounded(this@MainActivity, Pleos.withAlpha(p.brand, 0.14f), 12f)
                setPadding(dp(12), dp(4), dp(12), dp(4))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        }
        col.addView(nameRow, match())
        if (item.desc.isNotEmpty()) col.addView(text(item.desc, Type.LabelM, p.textTertiary, 1), match(top = 4))
        col.addView(text(won(item.price), Type.BodySStrong, p.textSecondary, 1), match(top = 6))
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        if (qty > 0) {
            addView(qtyStepper(qty, { changeQty(store, item, -1) }, { changeQty(store, item, +1) }),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(16) })
        } else {
            val cta = TextView(this@MainActivity).apply {
                text = "담기"
                pleos(Type.LabelMStrong, p.brandOnFill)
                background = Pleos.rounded(this@MainActivity, p.brandFill, 22f)
                setPadding(dp(28), dp(12), dp(28), dp(12))
            }
            addView(cta, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(16) })
        }

        val star = FrameLayout(this@MainActivity).apply {
            background = Pleos.rounded(this@MainActivity, Color.TRANSPARENT, 24f)
            isClickable = true
            contentDescription = if (isFavorite) "즐겨찾기 해제" else "즐겨찾기에 저장"
            setOnClickListener { toggleFavorite(store, item, isFavorite) }
            pressable(Color.TRANSPARENT, p.controlPressed)
        }
        val starIcon = icon(if (isFavorite) R.drawable.ic_star_fill else R.drawable.ic_star, if (isFavorite) p.favorite else p.textTertiary)
        star.addView(starIcon, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        if (isFavorite && popStar == item.id) {
            popStar = null
            starIcon.scaleX = 0.4f
            starIcon.scaleY = 0.4f
            starIcon.rotation = -40f
            starIcon.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(520)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.6f)).start()
        }
        addView(star, LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginStart = dp(4) })
    }

    private fun categoryBadge(category: String, size: Int): View {
        val res = when (category) {
            "치킨" -> R.drawable.ic_cat_chicken
            "피자" -> R.drawable.ic_cat_pizza
            "중식" -> R.drawable.ic_cat_chinese
            "국밥" -> R.drawable.ic_cat_soup
            else -> R.drawable.ic_store
        }
        val color = p.brand
        val glyph = (size * 0.55f).toInt()
        return FrameLayout(this).apply {
            background = Pleos.rounded(this@MainActivity, Pleos.blend(color, p.card, 0.14f), size * 0.3f)
            addView(icon(res, color), FrameLayout.LayoutParams(dp(glyph), dp(glyph), Gravity.CENTER))
        }
    }

    // ── 동작 ─────────────────────────────────────────────────────────

    private fun order(storeId: String, itemIds: List<String>): Boolean {
        if (state is PickupService.OrderState.Active || pending) {
            showToast("진행 중인 주문이 있습니다 · 취소 후 다시 주문해 주십시오")
            return false
        }
        pending = true
        renderHero()
        PickupService.order(this, storeId, itemIds)
        showToast("주문 완료 · 도착 시간에 맞춰 조리가 시작됩니다")
        return true
    }

    private fun orderCart(store: Store) {
        val itemIds = cart.flatMap { (id, qty) -> List(qty) { id } }
        if (itemIds.isEmpty()) return
        if (order(store.id, itemIds)) {
            clearCart()
            refreshMenus()
        }
    }

    private fun changeQty(store: Store, item: MenuItem, delta: Int) {
        if (cartStoreId != store.id) {
            if (delta < 0) return
            val replaced = cart.isNotEmpty()
            clearCart()
            cartStoreId = store.id
            if (replaced) showToast("한 매장 메뉴만 담을 수 있어 장바구니를 ${store.name}로 바꿨습니다")
        }
        val next = (cart[item.id] ?: 0) + delta
        if (next <= 0) cart.remove(item.id) else cart[item.id] = minOf(next, MAX_QTY)
        if (cart.isEmpty()) cartStoreId = null
        refreshMenus()
        renderHero()
    }

    private fun clearCart() {
        cart.clear()
        cartStoreId = null
    }

    private fun cartTotal(store: Store) = cart.entries.sumOf { (id, qty) ->
        (store.menu.firstOrNull { it.id == id }?.price ?: 0) * qty
    }

    private fun toggleFavorite(store: Store, item: MenuItem, wasFavorite: Boolean) {
        if (wasFavorite) {
            Favorites.clear(this)
            showToast("즐겨찾기 해제됨")
        } else {
            Favorites.save(this, store, item)
            popStar = item.id
            showToast("즐겨찾기에 저장됨 · “늘 먹던 거 포장해줘”로 음성 주문 가능")
        }
        refreshMenus()
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
        "SCHEDULED" -> if (o.secToCookStart >= 60) "${minutes(o.secToCookStart)}분 뒤 조리 시작 · 도착이 늦어지면 함께 조정" else "곧 조리 시작"
        "COOKING" -> "조리 중 · 도착 직전 완성 예정"
        "READY" -> "준비 완료 · 도착 즉시 수령"
        else -> PickupService.statusKo(o.status)
    }

    private fun statusColor(status: String) = when (status) {
        "SCHEDULED" -> p.brand
        "COOKING" -> p.cooking
        "CANCELLED" -> p.textTertiary
        else -> p.brand
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
        val normal = if (primary) p.brandFill else p.control
        val pressed = if (primary) p.brandFillPressed else p.controlPressed
        val fg = if (primary) p.brandOnFill else p.text
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
        private const val BANNER_DP = 232
        private const val LOGO_DP = 96
        private const val MAX_QTY = 9
        private const val PHOTO_SCALE = 1.16f
        private const val DETAIL_BANNER_DP = 300
        private const val PARALLAX = 0.12f
    }
}
