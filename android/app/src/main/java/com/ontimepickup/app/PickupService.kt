package com.ontimepickup.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 주문 1건의 수명주기를 맡는다: 매장을 경로에 넣고 → 남은 시간으로 주문 → 주행 내내 ETA를 서버에 갱신 →
 * 도착하면 매장에 알린다. 화면 없이 동작하므로 주행 중(Gleo 음성 주문)에도 쓸 수 있다.
 */
class PickupService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var navi: NaviTracker
    private var job: Job? = null
    private var orderId: String? = null

    override fun onCreate() {
        super.onCreate()
        navi = NaviTracker(this)
        startForeground(NOTI_ID, notification("픽업 준비 중"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ORDER -> {
                val storeId = intent.getStringExtra(EXTRA_STORE) ?: return START_NOT_STICKY
                val itemId = intent.getStringExtra(EXTRA_ITEM) ?: return START_NOT_STICKY
                job?.cancel()
                job = scope.launch { runOrder(storeId, itemId) }
            }
            ACTION_CANCEL -> scope.launch { cancelOrder() }
        }
        return START_NOT_STICKY
    }

    private suspend fun runOrder(storeId: String, itemId: String) {
        val store = runCatching { Api.stores().first { it.id == storeId } }.getOrElse {
            publish(OrderState.Error("매장 정보를 불러오지 못했습니다"))
            return
        }
        navi.start { navi.routeVia(store) }

        // 경로 탐색이 끝나야 남은 시간이 나온다. 내비가 응답하지 않으면(에뮬레이터 등) 추정치로 진행.
        val firstRemain = withTimeoutOrNull(NAVI_WAIT_MS) { awaitRemain() }
        val estimated = firstRemain == null
        var remain = firstRemain ?: FALLBACK_REMAIN_SEC

        val order = runCatching { Api.createOrder(store.id, itemId, Favorites.carPlate(this), remain) }.getOrElse {
            publish(OrderState.Error("주문 전송 실패: 네트워크를 확인하세요"))
            return
        }
        orderId = order.id
        publish(OrderState.Active(order, estimated))
        updateNotification("${store.name} · 도착 ${order.secToArrive / 60}분 전")

        while (true) {
            delay(POLL_MS)
            if (estimated) {
                remain = (remain - (POLL_MS / 1000).toInt()).coerceAtLeast(0)
            } else {
                navi.refresh()
            }
            when (val e = navi.events.value) {
                is NaviTracker.Event.Remain -> if (!estimated) remain = e.seconds
                NaviTracker.Event.Arrived -> break
                else -> Unit
            }
            if (estimated && remain == 0) break
            // 네트워크가 잠깐 끊겨도 다음 주기에 다시 보낸다.
            runCatching { Api.updateEta(order.id, remain) }.onSuccess {
                publish(OrderState.Active(it, estimated))
                updateNotification("${store.name} · ${it.secToArrive / 60}분 후 도착 · ${statusKo(it.status)}")
            }
        }
        runCatching { Api.arrived(order.id) }.onSuccess { publish(OrderState.Arrived(it)) }
        updateNotification("${store.name} 도착 — 매장에 알렸습니다")
        navi.clear()
    }

    private suspend fun awaitRemain(): Int {
        while (true) {
            navi.refresh()
            val e = withTimeoutOrNull(2_000) { navi.events.filterNotNull().first { it is NaviTracker.Event.Remain } }
            if (e is NaviTracker.Event.Remain) return e.seconds
        }
    }

    private suspend fun cancelOrder() {
        val id = orderId ?: return
        job?.cancel()
        runCatching { Api.cancel(id) }
        navi.clear()
        publish(OrderState.Idle)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        navi.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "픽업 진행", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, notification(text))
    }

    sealed interface OrderState {
        data object Idle : OrderState
        data class Active(val order: OrderView, val estimated: Boolean) : OrderState
        data class Arrived(val order: OrderView) : OrderState
        data class Error(val message: String) : OrderState
    }

    companion object {
        private const val TAG = "PickupService"
        private const val CHANNEL = "pickup"
        private const val NOTI_ID = 1
        private const val POLL_MS = 15_000L
        private const val NAVI_WAIT_MS = 20_000L
        private const val FALLBACK_REMAIN_SEC = 20 * 60

        const val ACTION_ORDER = "com.ontimepickup.app.ORDER"
        const val ACTION_CANCEL = "com.ontimepickup.app.CANCEL"
        const val EXTRA_STORE = "store"
        const val EXTRA_ITEM = "item"

        private val _state = MutableStateFlow<OrderState>(OrderState.Idle)
        val state: StateFlow<OrderState> = _state

        private fun publish(s: OrderState) {
            Log.d(TAG, "state $s")
            _state.value = s
        }

        fun order(context: Context, storeId: String, itemId: String) {
            context.startForegroundService(
                Intent(context, PickupService::class.java)
                    .setAction(ACTION_ORDER)
                    .putExtra(EXTRA_STORE, storeId)
                    .putExtra(EXTRA_ITEM, itemId),
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, PickupService::class.java).setAction(ACTION_CANCEL))
        }

        /** 도착 확인·오류 확인 뒤 화면을 대기 상태로 되돌린다. */
        fun reset() = publish(OrderState.Idle)

        fun statusKo(status: String) = when (status) {
            "SCHEDULED" -> "조리 대기"
            "COOKING" -> "조리 중"
            "READY" -> "준비 완료"
            "PICKED_UP" -> "전달 완료"
            "CANCELLED" -> "취소됨"
            else -> status
        }
    }
}
