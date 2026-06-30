package com.seres.evtoldemo.data.auth

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response

/** 给每个请求注入 Authorization: Bearer <token>（登录后）。 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val authStore: AuthStore
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = authStore.token
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
