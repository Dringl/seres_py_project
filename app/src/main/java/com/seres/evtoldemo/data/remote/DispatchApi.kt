package com.seres.evtoldemo.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface DispatchApi {
    @GET("vertiports")
    suspend fun getVertiports(): List<VertiportDto>

    @POST("vehicles/nearby")
    suspend fun getNearbyVehicles(@Body request: NearbyRequestDto): List<EvtolDto>

    @POST("price/estimate")
    suspend fun estimatePrice(@Body request: EstimateRequestDto): PriceEstimateDto

    @POST("orders")
    suspend fun createOrder(@Body request: CreateOrderRequestDto): OrderDto

    @GET("orders/{id}")
    suspend fun getOrder(@Path("id") orderId: String): OrderDto

    @GET("orders")
    suspend fun getOrders(): List<OrderDto>

    @POST("orders/{id}/board")
    suspend fun board(@Path("id") orderId: String): OrderDto

    @POST("orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") orderId: String): OrderDto
}
