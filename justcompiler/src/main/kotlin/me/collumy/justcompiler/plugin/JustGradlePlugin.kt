package me.collumy.justcompiler.plugin

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class JustGradlePlugin : KotlinCompilerPluginSupportPlugin {
    private val version = JustGradlePlugin::class.java.`package`.implementationVersion ?: "1.0.0"

    override fun apply(target: Project) {}

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>) = kotlinCompilation.name == "main"
    override fun getCompilerPluginId() = "me.collumy.justcompiler"
    override fun getPluginArtifact() = SubpluginArtifact("me.collumy", "justcompiler", version)

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider { emptyList() }
}