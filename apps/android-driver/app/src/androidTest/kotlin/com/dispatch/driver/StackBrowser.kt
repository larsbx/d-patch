package com.dispatch.driver

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The system browser, the one stand-in in the stack tests: biz-kit's login,
 * the provider, and the callback back to the app's scheme.
 */
object StackBrowser {

    tailrec fun follow(url: String): String {
        if (url.startsWith("${BuildConfig.APPLICATION_ID}:")) return url
        val next = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val location = checkNotNull(response.header("Location")) { "no redirect from $url: ${response.code}" }
            if (location.startsWith("${BuildConfig.APPLICATION_ID}:")) location else checkNotNull(response.request.url.resolve(location)).toString()
        }
        return follow(next)
    }

    private val http = AcceptanceTrace.client().newBuilder()
        .followRedirects(false)
        .cookieJar(object : CookieJar {
            private val jar = mutableListOf<Cookie>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                jar.removeAll { kept -> cookies.any { it.name == kept.name } }
                jar += cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = jar.filter { it.matches(url) }
        })
        .build()
}
