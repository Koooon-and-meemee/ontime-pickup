package com.ontimepickup.app

import android.content.Context

/** 즐겨찾기(음성으로는 "늘 먹던 거")와 차량 번호. 주행 중 음성 주문은 이 값만으로 끝나야 하므로 주차 중에 미리 저장해 둔다. */
object Favorites {
    private const val PREFS = "favorites"

    data class Favorite(val storeId: String, val itemId: String, val label: String, val category: String)

    fun save(context: Context, store: Store, item: MenuItem) {
        prefs(context).edit()
            .putString("storeId", store.id)
            .putString("itemId", item.id)
            .putString("label", "${store.name} ${item.name}")
            .putString("category", store.category)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove("storeId").remove("itemId").remove("label").remove("category").apply()
    }

    fun get(context: Context): Favorite? {
        val p = prefs(context)
        val storeId = p.getString("storeId", null) ?: return null
        val itemId = p.getString("itemId", null) ?: return null
        return Favorite(storeId, itemId, p.getString("label", "")!!, p.getString("category", "")!!)
    }

    // 실차에서는 Vehicle SDK의 VIN/등록 정보 대신 사용자가 처음 실행 때 입력한다.
    fun carPlate(context: Context): String = prefs(context).getString("plate", "12가 3456")!!

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
