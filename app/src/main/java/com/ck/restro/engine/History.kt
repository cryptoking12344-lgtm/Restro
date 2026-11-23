package com.ck.restro.engine

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Restored photos this app saved, newest first. */
object History {
    class Item(val uri: Uri, val info: String, val time: Long)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("restro_history", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Item> {
        val arr = runCatching { JSONArray(prefs(ctx).getString("items", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Item(Uri.parse(o.getString("uri")), o.optString("info"), o.optLong("time"))
        }
    }

    @Synchronized
    fun add(ctx: Context, uri: Uri, info: String) {
        val list = all(ctx).toMutableList()
        list.add(0, Item(uri, info, System.currentTimeMillis()))
        save(ctx, list.take(300))
    }

    @Synchronized
    fun remove(ctx: Context, uri: Uri) = save(ctx, all(ctx).filter { it.uri != uri })

    private fun save(ctx: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri.toString()).put("info", it.info).put("time", it.time)) }
        prefs(ctx).edit().putString("items", arr.toString()).apply()
    }
}
