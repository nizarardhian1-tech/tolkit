package com.mondns.app

import retrofit2.Call
import retrofit2.http.*

interface ApiService {
    @GET("items")
    fun getItems(): Call<List<String>>
}
