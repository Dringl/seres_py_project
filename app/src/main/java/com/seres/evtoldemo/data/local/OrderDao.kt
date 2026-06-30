package com.seres.evtoldemo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(order: OrderEntity)

    @Query("SELECT * FROM orders ORDER BY createdAt DESC")
    suspend fun getAll(): List<OrderEntity>

    @Query("DELETE FROM orders")
    suspend fun deleteAll()
}
