package pl.sp8mb.owrx.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import pl.sp8mb.owrx.scanner.ScannerEngine
import pl.sp8mb.owrx.scanner.SessionScannerHost
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideScannerEngine(host: SessionScannerHost): ScannerEngine =
        ScannerEngine(host, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            // NO pingInterval: the OpenWebRX python websocket server does not
            // answer client pings (verified empirically — browsers never ping,
            // so the bug is invisible in web use). OkHttp would kill the
            // connection on every missed pong, causing a reconnect loop.
            // Dead links are detected by OwrxSession's 30 s silence watchdog.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
}
