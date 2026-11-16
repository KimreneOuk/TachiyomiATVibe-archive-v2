package eu.kanade.translation.models

import android.content.Context
import android.net.ConnectivityManager
import eu.kanade.translation.models.ModelPackDescriptor
import eu.kanade.translation.models.PackSourceKind
import eu.kanade.translation.models.imports.ImportSources
import eu.kanade.translation.models.imports.MutableCatalog
import eu.kanade.translation.models.source.AssetPackSource
import eu.kanade.translation.models.source.HttpPackSource
import eu.kanade.translation.models.source.ModelPackSource
import java.io.File

/**
 * Process-wide holder. Pack state is disk-as-truth, so a fresh instance after
 * process death rebuilds everything, and installed packs survive app updates
 * because nothing is keyed to app version or update timestamps.
 */
object ModelPackManagerProvider {

    @Volatile
    private var instance: ModelPackManagerImpl? = null

    private val catalog = MutableCatalog(ModelPackCatalog.defaultEmbedded())
    private val httpSource = HttpPackSource()

    /** Shared descriptor registry; imported variants register here at import time. */
    fun mutableCatalog(): MutableCatalog = catalog

    fun get(context: Context): ModelPackManagerImpl = instance ?: synchronized(this) {
        instance ?: create(context).also { instance = it }
    }

    fun peek(): ModelPackManagerImpl? = instance

    private fun create(context: Context): ModelPackManagerImpl = ModelPackManagerImpl(
        rootDir = File(context.noBackupFilesDir, "model-packs"),
        bundledCacheRoot = File(context.noBackupFilesDir, "tachiyomiat-models"),
        catalogProvider = { catalog.snapshot() },
        sourceFor = { descriptor: ModelPackDescriptor -> sourceFor(descriptor, context) },
        networkMeteredProvider = {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.isActiveNetworkMetered ?: true
        },
    )

    private fun sourceFor(descriptor: ModelPackDescriptor, context: Context): ModelPackSource? = when (descriptor.sourceKind) {
        PackSourceKind.ASSET_MIRROR -> AssetPackSource(context)
        PackSourceKind.HTTP -> httpSource
        // User imports and the sandbox register their sources in the router for the install call only.
        else -> ImportSources.active[descriptor.id]
    }
}
