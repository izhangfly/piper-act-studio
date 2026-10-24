package com.nervus.packaging.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import java.io.Serializable

abstract class NspkgExtension : Serializable {

    abstract val packageId: Property<String>
    abstract val version: Property<String>
    abstract val versionCode: Property<Long>
    abstract val label: Property<String>

    abstract val labels: MapProperty<String, String>
    abstract val icon: Property<String>

    abstract val minNervusApi: Property<Int>
    abstract val targetNervusApi: Property<Int>
    abstract val supportedAbis: ListProperty<String>

    abstract val permissions: ListProperty<String>

    val runtimeDeps: RuntimeDepsExtension = RuntimeDepsExtension()
    val usesFeatures: UsesFeaturesDsl = UsesFeaturesDsl()
    val signing: SigningDsl = SigningDsl()
    val components: ComponentDsl = ComponentDsl()

    /**
     * Provider contract artifacts. A package that exports interfaces [must] configure this; see [ProviderDsl].
     */
    val provider: ProviderDsl = ProviderDsl()

    fun provider(configure: ProviderDsl.() -> Unit) {
        provider.configure()
    }

    class RuntimeDepsExtension : Serializable {
        var minJavaRelease: Int? = null
    }
}
