package com.gametodolist.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long,          // ARGB 十六进制，如 0xFF7FB69E
    val icon: String = "",    // emoji 或图标名，可空
    val sortOrder: Int = 0
)
