package com.mondns.app

import androidx.room.*

@Dao
interface UserDao {
    @Insert
    suspend fun insert(user: User)
    @Query("SELECT * FROM users ORDER BY id ASC")
    suspend fun getAll(): List<User>
    @Delete
    suspend fun delete(user: User)
}
