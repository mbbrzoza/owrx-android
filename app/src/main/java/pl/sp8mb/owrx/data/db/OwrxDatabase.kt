package pl.sp8mb.owrx.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ServerEntity::class, ScanHitEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class OwrxDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun scanHitDao(): ScanHitDao
}
