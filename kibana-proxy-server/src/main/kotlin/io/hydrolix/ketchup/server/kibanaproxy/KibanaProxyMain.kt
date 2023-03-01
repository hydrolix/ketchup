package io.hydrolix.ketchup.server.kibanaproxy

import java.net.CookieManager

/**
 * TODO:
 *  * Use ktor instead of java.net.http.HttpClient for client, not just server
 *  * Actually intercept search/data requests, and:
 *    1. keep the cookies in our session, not global
 *    2. optionally save out request/response pairs out to files, to prepare for ongoing development - should be a separate server?
 *    3. interpret queries, rewrite to CH-flavoured SQL, dispatch to CH
 */
object KibanaProxyMain {
    @Deprecated("This is sharing the same cookies across all our sessions, but necessary for demos; cut it out")
    val cookieManager = CookieManager()

    @JvmStatic
    fun main(args: Array<String>) {
        io.ktor.server.netty.EngineMain.main(args)
    }
}