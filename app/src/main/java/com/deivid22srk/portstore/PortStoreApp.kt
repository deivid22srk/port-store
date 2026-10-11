package com.deivid22srk.portstore

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.deivid22srk.portstore.catalog.CatalogRepository
import com.deivid22srk.portstore.core.DownloadRepository
import com.deivid22srk.portstore.db.AppDatabase
import com.deivid22srk.portstore.github.ReleaseResolver
import com.deivid22srk.portstore.github.VersionResolver
import com.deivid22srk.portstore.install.InstalledAppsMonitor
import com.deivid22srk.portstore.service.Notifications
import com.deivid22srk.portstore.settings.SettingsRepository
import com.deivid22srk.portstore.update.SelfUpdateManager
import com.deivid22srk.portstore.virtual.VirtualBootstrap
import com.deivid22srk.portstore.virtual.VirtualManager
import com.deivid22srk.portstore.work.UpdateCheckWorker
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Service locator simples (DI sem geração de código, para builds previsíveis). */
object AppGraph {
    lateinit var settings: SettingsRepository
        private set
    lateinit var catalog: CatalogRepository
        private set
    lateinit var downloads: DownloadRepository
        private set
    lateinit var resolver: ReleaseResolver
        private set
    lateinit var versions: VersionResolver
        private set
    lateinit var installedApps: InstalledAppsMonitor
        private set
    lateinit var selfUpdate: SelfUpdateManager
        private set
    lateinit var db: AppDatabase
        private set

    /**
     * Modo Instalação Virtual. Criado sempre (é leve e não toca no motor),
     * mas o motor só é inicializado se o modo estiver ativado — ver
     * [VirtualBootstrap]. Com o modo desligado, tudo aqui é no-op barato.
     */
    lateinit var virtual: VirtualManager
        private set

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Só roda no processo principal — os processos do motor virtual (:black,
     * :p0..:p49) não chamam isto (ver [PortStoreApp.onCreate]): Room,
     * WorkManager, DataStore e receivers pertencem ao app de UI.
     */
    fun init(context: Context) {
        if (this::settings.isInitialized) return
        db = AppDatabase.build(context)
        settings = SettingsRepository(context)
        catalog = CatalogRepository(context, okHttp, db)
        resolver = ReleaseResolver(okHttp)
        versions = VersionResolver(okHttp, db, settings)
        installedApps = InstalledAppsMonitor(context)
        selfUpdate = SelfUpdateManager(context, okHttp, settings)
        downloads = DownloadRepository(context, db, settings)
        downloads.initialize()
        installedApps.start()
        virtual = VirtualManager(context)
        UpdateCheckWorker.schedule(context)
    }
}

class PortStoreApp : Application(), ImageLoaderFactory {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Bootstrap preguiçoso do motor de virtualização: só roda com o modo
        // ativado (flag espelho em SharedPreferences) e nunca derruba o app.
        if (base != null) {
            VirtualBootstrap.attachBaseContext(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Processos do motor virtual (:black servidor, :p0..:p49 onde os
        // jogos rodam) precisam APENAS do bootstrap do Bcore — o init pesado
        // do app (Room, WorkManager, DataStore, receivers, rede) fica no
        // processo principal. Isso enxuga o servidor do motor e elimina uma
        // classe inteira de crashes que matavam o processo :black logo após
        // ele registrar os serviços (o Application.onCreate roda depois dos
        // ContentProviders — exatamente a janela do DeadObjectException
        // observado nos logs do aparelho).
        if (isEngineProcess()) {
            VirtualBootstrap.onCreate(this)
            return
        }
        AppGraph.init(this)
        VirtualBootstrap.onCreate(this)
        Notifications.createChannels(this)
        registerNetworkMonitor()
        trackCatalogPackages()
    }

    /**
     * Verdadeiro em processos que não são o principal (servidor :black e
     * processos de jogo :pN declarados pelo Bcore). Fallback conservador:
     * se o nome do processo não puder ser lido, assumimos o principal.
     */
    private fun isEngineProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                java.io.File("/proc/self/cmdline").readText().trim('\u0000').trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        } ?: return false
        return name != packageName
    }

    /** Observa o catálogo e mantém os pacotes rastreados pelo monitor atualizados. */
    private fun trackCatalogPackages() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            AppGraph.catalog.catalog.collect { cat ->
                AppGraph.installedApps.track(
                    cat?.games?.mapNotNull { it.primaryPackage }?.toSet().orEmpty(),
                )
            }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    /** Avisa o motor Rust sobre mudanças de conectividade e acorda a fila. */
    private fun registerNetworkMonitor() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notifyNative()
                com.deivid22srk.portstore.service.DownloadService.ensureStarted(applicationContext)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                notifyNative()
            }

            override fun onLost(network: Network) {
                notifyNative()
            }
        }
        notifyNative()
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private fun notifyNative() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val connected = caps != null
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        AppGraph.downloads.setNetworkState(connected, unmetered)
    }
}
