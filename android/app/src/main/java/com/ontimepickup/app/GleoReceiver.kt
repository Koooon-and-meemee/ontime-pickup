package com.ontimepickup.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo AI 음성 명령 수신(Gleo Interface, component = "service").
 * function 이름이 intent action으로, 파라미터는 ACTION_PARAM(JSON 문자열)으로 온다.
 * Gleo는 7초 안에 응답이 없으면 취소하므로 결과는 곧바로 돌려준다.
 */
class GleoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val params = runCatching { JSONObject(intent.getStringExtra("ACTION_PARAM") ?: "{}") }
            .getOrDefault(JSONObject())
        when (action) {
            "ORDER_USUAL" -> orderUsual(context, params)
            "FIND_PICKUP_STORE" -> findStores(context, params)
            "CANCEL_PICKUP_ORDER" -> {
                PickupService.cancel(context)
                respond(context, action, "success", "The pickup order has been cancelled.")
            }
        }
    }

    private fun orderUsual(context: Context, params: JSONObject) {
        val fav = Favorites.get(context)
        if (fav == null) {
            respond(context, "ORDER_USUAL", "fail",
                "No saved favorite order. The user should save one in the app while parked.")
            return
        }
        val wanted = params.optString("favoriteName")
        if (wanted.isNotBlank() && !fav.label.contains(wanted) && !fav.category.contains(wanted)) {
            respond(context, "ORDER_USUAL", "asking",
                "The saved favorite is different from the request. Confirm ordering the saved favorite.",
                JSONArray().put(JSONObject().put("savedFavorite", fav.label)).toString())
            return
        }
        runCatching { PickupService.order(context, fav.storeId, fav.itemId) }
            .onSuccess {
                respond(context, "ORDER_USUAL", "success",
                    "The usual order was placed. Cooking will start so that it is ready when the car arrives.",
                    JSONArray().put(JSONObject().put("order", fav.label)).toString())
            }
            .onFailure {
                Log.e(TAG, "start service failed", it)
                respond(context, "ORDER_USUAL", "fail", "The order could not be started.")
            }
    }

    private fun findStores(context: Context, params: JSONObject) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            val stores = withTimeoutOrNull(5_000) { runCatching { Api.stores() }.getOrNull() }
            if (stores == null) {
                respond(context, "FIND_PICKUP_STORE", "fail", "The store list could not be loaded.")
            } else {
                val category = params.optString("category", "any")
                val data = JSONArray()
                stores.filter { category == "any" || CATEGORY[category] == it.category }.take(3).forEach {
                    data.put(JSONObject().put("name", it.name).put("prepMinutes", it.prepMin))
                }
                respond(context, "FIND_PICKUP_STORE", "success",
                    "Found ${data.length()} pickup stores near the route. The user can pick one when the car is stopped.",
                    data.toString())
            }
            pending.finish()
        }
    }

    private fun respond(context: Context, function: String, status: String, message: String, data: String? = null) {
        val response = JSONObject()
            .put("functionName", function)
            .put("status", status)
            .put("message", message)
        data?.let { response.put("data", it) }
        context.sendBroadcast(
            Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
                .putExtra("packageName", context.packageName)
                .putExtra("response", response.toString()),
        )
    }

    private companion object {
        const val TAG = "GleoReceiver"
        val CATEGORY = mapOf("chicken" to "치킨", "pizza" to "피자", "chinese" to "중식", "korean_soup" to "국밥")
    }
}
