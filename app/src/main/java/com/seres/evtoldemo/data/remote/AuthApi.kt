package com.seres.evtoldemo.data.remote

import retrofit2.http.Body
import retrofit2.http.POST

data class AuthRequestDto(val username: String, val password: String)

data class AuthResponseDto(val token: String, val userId: Int, val username: String)

interface AuthApi {
    @POST("auth/register")
    suspend fun register(@Body body: AuthRequestDto): AuthResponseDto

    @POST("auth/login")
    suspend fun login(@Body body: AuthRequestDto): AuthResponseDto
}
