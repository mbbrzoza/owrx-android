package pl.sp8mb.owrx.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import pl.sp8mb.owrx.data.db.OwrxDatabase
import pl.sp8mb.owrx.data.db.ScanHitDao
import pl.sp8mb.owrx.data.db.ServerDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE servers ADD COLUMN adminUser TEXT")
            db.execSQL("ALTER TABLE servers ADD COLUMN adminPassword TEXT")
        }
    }

    private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE servers ADD COLUMN magicKey TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OwrxDatabase =
        Room.databaseBuilder(context, OwrxDatabase::class.java, "owrx.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideServerDao(db: OwrxDatabase): ServerDao = db.serverDao()

    @Provides
    fun provideScanHitDao(db: OwrxDatabase): ScanHitDao = db.scanHitDao()
}
