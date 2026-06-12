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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OwrxDatabase =
        Room.databaseBuilder(context, OwrxDatabase::class.java, "owrx.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideServerDao(db: OwrxDatabase): ServerDao = db.serverDao()

    @Provides
    fun provideScanHitDao(db: OwrxDatabase): ScanHitDao = db.scanHitDao()
}
