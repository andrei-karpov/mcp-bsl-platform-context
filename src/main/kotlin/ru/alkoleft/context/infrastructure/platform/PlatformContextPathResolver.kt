/*
 * Copyright (c) 2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context.infrastructure.platform

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

object PlatformContextPathResolver {
    private const val CONTEXT_FILE_NAME = "shcntx_ru.hbk"
    private val versionRegex = Regex("""\d+\.\d+\.\d+\.\d+""")

    fun resolve(
        explicitPath: String?,
        explicitRoot: String?,
        explicitVersion: String?,
        cacheEnabled: Boolean,
    ): Path {
        val source =
            findContextFile(
                explicitPath = explicitPath?.takeIf(String::isNotBlank),
                explicitRoot = explicitRoot?.takeIf(String::isNotBlank),
                explicitVersion = explicitVersion?.takeIf(String::isNotBlank),
            )

        if (!cacheEnabled) {
            return source.parent
        }

        val cacheDir = cacheDirectory(source)
        val cacheFile = cacheDir.resolve(CONTEXT_FILE_NAME)
        cacheDir.createDirectories()

        if (shouldRefreshCache(source, cacheFile)) {
            logger.info { "Кэширование контекста платформы: $source -> $cacheFile" }
            Files.copy(source, cacheFile, StandardCopyOption.REPLACE_EXISTING)
        }

        return cacheDir
    }

    private fun findContextFile(
        explicitPath: String?,
        explicitRoot: String?,
        explicitVersion: String?,
    ): Path {
        val envPath = env("PLATFORM_CONTEXT_PATH")
        val envRoot = env("BSL_PLATFORM_ROOT")
        val envVersion = env("BSL_PLATFORM_VERSION")

        (explicitPath ?: envPath)
            ?.let(::Path)
            ?.let(::findContextFileInPath)
            ?.let { return it }

        val rootCandidates =
            sequence {
                explicitRoot?.let { yield(Path(it)) }
                envRoot?.let { yield(Path(it)) }
                yieldAll(defaultPlatformRoots())
            }.distinct().filter { it.exists() && it.isDirectory() }.toList()

        val version = explicitVersion ?: envVersion
        val platformDir =
            rootCandidates
                .flatMap(::versionDirectories)
                .filter { version == null || it.name == version }
                .maxWithOrNull { left, right -> compareVersions(left.name, right.name) }
                ?: throw IllegalStateException(
                    "Не удалось найти установленную платформу 1С. " +
                        "Укажите --platform-path, PLATFORM_CONTEXT_PATH или BSL_PLATFORM_ROOT.",
                )

        return findContextFileInPath(platformDir)
            ?: throw IllegalStateException("Не удалось найти $CONTEXT_FILE_NAME в $platformDir")
    }

    private fun findContextFileInPath(path: Path): Path? {
        if (path.isRegularFile() && path.name == CONTEXT_FILE_NAME) {
            return path
        }

        val directCandidates =
            listOf(
                path.resolve(CONTEXT_FILE_NAME),
                path.resolve("bin").resolve(CONTEXT_FILE_NAME),
            )

        directCandidates.firstOrNull { it.isRegularFile() }?.let { return it }

        if (!path.isDirectory()) {
            return null
        }

        return Files
            .walk(path)
            .use { files ->
                files
                    .filter { it.isRegularFile() }
                    .filter { it.name == CONTEXT_FILE_NAME }
                    .findFirst()
                    .orElse(null)
            }
    }

    private fun defaultPlatformRoots(): Sequence<Path> =
        sequence {
            env("ProgramFiles")?.let { yield(Path(it).resolve("1cv8")) }
            env("ProgramFiles(x86)")?.let { yield(Path(it).resolve("1cv8")) }
            yield(Path("C:\\Program Files\\1cv8"))
            yield(Path("C:\\Program Files (x86)\\1cv8"))
            yield(Path("/mnt/c/Program Files/1cv8"))
            yield(Path("/mnt/c/Program Files (x86)/1cv8"))
            yield(Path("/opt/1cv8/x86_64"))
            yield(Path("/opt/1cv8/i386"))
            yield(Path("/opt/1cv8"))
        }

    private fun versionDirectories(root: Path): List<Path> =
        Files
            .list(root)
            .use { files ->
                files
                    .filter { it.isDirectory() }
                    .filter { versionRegex.matches(it.name) }
                    .toList()
            }

    private fun cacheDirectory(source: Path): Path {
        val version = source.parent?.parent?.name?.takeIf { versionRegex.matches(it) } ?: "custom"
        val cacheRoot =
            env("BSL_PLATFORM_CONTEXT_CACHE")
                ?.let(::Path)
                ?: userCacheRoot().resolve("mcp-bsl-platform-context")

        return cacheRoot.resolve(version)
    }

    private fun userCacheRoot(): Path {
        env("XDG_CACHE_HOME")?.let { return Path(it) }
        if (isWindows()) {
            env("LOCALAPPDATA")?.let { return Path(it).resolve("mcp-bsl-context").resolve("cache") }
        }
        return Path(System.getProperty("user.home")).resolve(".cache")
    }

    private fun shouldRefreshCache(
        source: Path,
        cacheFile: Path,
    ): Boolean {
        if (!cacheFile.exists()) return true
        if (Files.size(source) != Files.size(cacheFile)) return true
        return Files.getLastModifiedTime(source) > Files.getLastModifiedTime(cacheFile)
    }

    private fun versionKey(version: String): List<Int> = version.split('.').map { it.toIntOrNull() ?: 0 }

    private fun compareVersions(
        left: String,
        right: String,
    ): Int {
        val leftKey = versionKey(left)
        val rightKey = versionKey(right)
        val maxSize = maxOf(leftKey.size, rightKey.size)

        for (index in 0 until maxSize) {
            val diff = leftKey.getOrElse(index) { 0 } - rightKey.getOrElse(index) { 0 }
            if (diff != 0) {
                return diff
            }
        }

        return 0
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf(String::isNotBlank)

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)
}
