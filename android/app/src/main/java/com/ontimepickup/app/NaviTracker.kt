package com.ontimepickup.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 픽업 매장까지 남은 시간을 내비에서 받아온다.
 *
 * 안내 중인 경로가 있으면 매장을 첫 번째 경유지로 끼워 넣고(퇴근길 그대로 가면서 들르기),
 * 경로가 없으면 매장을 목적지로 설정한다. 남은 시간은 getWaypointInfo()/getDestinationInfo()
 * 응답 콜백으로만 오므로 [refresh]를 주기적으로 불러야 한다.
 */
class NaviTracker(context: Context) {

    sealed interface Event {
        data class Remain(val seconds: Int) : Event
        data object Arrived : Event
        data class Failed(val reason: String) : Event
    }

    private val navi = NaviHelper(context.applicationContext)
    private val _events = MutableStateFlow<Event?>(null)
    val events: StateFlow<Event?> = _events

    private var isRouting = false
    private var target: Store? = null
    private var asWaypoint = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            isRouting = routeStateInfo.isRouting
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            val store = target ?: return
            waypointInfoList.firstOrNull { it.name == store.name }
                ?.let { _events.value = Event.Remain(it.remainTime) }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val store = target ?: return
            if (!asWaypoint && destinationInfo.name == store.name) {
                _events.value = Event.Remain(destinationInfo.remainTime)
            }
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            if (asWaypoint && waypointArrivedInfo.name == target?.name) _events.value = Event.Arrived
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            if (!asWaypoint) _events.value = Event.Arrived
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error $errorCode")
            _events.value = Event.Failed(errorCode.name)
        }
    }

    fun start(onReady: () -> Unit) {
        navi.initialize(
            onConnected = {
                navi.addListener(listener)
                navi.getRouteStateInfo() // 지금 안내 중인 경로가 있는지 → onRouteStateInfo
                onReady()
            },
            onFailed = { _events.value = Event.Failed("NaviHelper connect: ${it.message}") },
        )
    }

    /** 매장을 경로에 넣는다. 안내 중이면 경유지, 아니면 목적지. */
    fun routeVia(store: Store) {
        target = store
        asWaypoint = isRouting
        if (asWaypoint) {
            navi.addWaypoint(
                RequestWaypointInfo(
                    latitude = store.lat,
                    longitude = store.lng,
                    poiName = store.name,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = store.id,
                    address = store.address,
                    poiSubId = "0",
                ),
            )
        } else {
            navi.requestRoute(
                RouteInfo(
                    latitude = store.lat,
                    longitude = store.lng,
                    poiName = store.name,
                    poiId = store.id,
                    address = store.address,
                    poiSubId = "0",
                ),
            )
        }
    }

    fun refresh() {
        if (asWaypoint) navi.getWaypointInfo() else navi.getDestinationInfo()
    }

    fun clear() {
        if (target != null && asWaypoint) navi.removeWaypoint(WaypointIndex.FIRST)
        target = null
    }

    fun release() {
        navi.removeListener(listener)
        navi.release()
    }

    private companion object {
        const val TAG = "NaviTracker"
    }
}
