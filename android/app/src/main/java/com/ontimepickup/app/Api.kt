package com.ontimepickup.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MenuItem(
    val id: String,
    val name: String,
    val price: Int,
    val group: String = "",
    val desc: String = "",
    val popular: Boolean = false,
)

data class Store(
    val id: String,
    val name: String,
    val category: String,
    val prepMin: Int,
    val prepMax: Int,
    val lat: Double,
    val lng: Double,
    val address: String,
    val menu: List<MenuItem>,
    val rating: Double = 0.0,
    val reviews: Int = 0,
)

data class OrderView(
    val id: String,
    val status: String,
    val storeName: String,
    val secToCookStart: Int,
    val secToArrive: Int,
    val total: Int,
)

/** 목업 서버(server/app.py) 클라이언트. 주행 중 네트워크가 끊길 수 있으므로 호출부는 실패를 견뎌야 한다. */
object Api {
    private const val TIMEOUT_MS = 5_000

    suspend fun stores(): List<Store> = withContext(Dispatchers.IO) {
        val arr = JSONArray(request("GET", "/stores"))
        (0 until arr.length()).map { i -> parseStore(arr.getJSONObject(i)) }
    }

    suspend fun createOrder(storeId: String, itemIds: List<String>, carPlate: String, remainSec: Int): OrderView =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("storeId", storeId)
                .put("itemIds", JSONArray(itemIds))
                .put("carPlate", carPlate)
                .put("remainTimeSec", remainSec)
            parseOrder(JSONObject(request("POST", "/orders", body)))
        }

    suspend fun updateEta(orderId: String, remainSec: Int): OrderView = withContext(Dispatchers.IO) {
        parseOrder(JSONObject(request("POST", "/orders/$orderId/eta", JSONObject().put("remainTimeSec", remainSec))))
    }

    suspend fun arrived(orderId: String): OrderView = withContext(Dispatchers.IO) {
        parseOrder(JSONObject(request("POST", "/orders/$orderId/arrived", JSONObject())))
    }

    suspend fun cancel(orderId: String): OrderView = withContext(Dispatchers.IO) {
        parseOrder(JSONObject(request("POST", "/orders/$orderId/status/CANCELLED", JSONObject())))
    }

    private fun request(method: String, path: String, body: JSONObject? = null): String {
        val conn = URL(BuildConfig.SERVER_URL + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} $path")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseStore(o: JSONObject): Store {
        val menu = o.getJSONArray("menu")
        return Store(
            id = o.getString("id"),
            name = o.getString("name"),
            category = o.getString("category"),
            prepMin = o.getInt("prepMin"),
            prepMax = o.optInt("prepMax", o.getInt("prepMin")),
            lat = o.getDouble("lat"),
            lng = o.getDouble("lng"),
            address = o.getString("address"),
            menu = (0 until menu.length()).map { i ->
                val m = menu.getJSONObject(i)
                MenuItem(
                    m.getString("id"), m.getString("name"), m.getInt("price"),
                    group = m.optString("group"), desc = m.optString("desc"), popular = m.optBoolean("popular"),
                )
            },
            rating = o.optDouble("rating", 0.0),
            reviews = o.optInt("reviews", 0),
        )
    }

    private fun parseOrder(o: JSONObject) = OrderView(
        id = o.getString("id"),
        status = o.getString("status"),
        storeName = o.getJSONObject("store").getString("name"),
        secToCookStart = o.getInt("secToCookStart"),
        secToArrive = o.getInt("secToArrive"),
        total = o.optInt("total"),
    )
}
