/*
 * Copyright (c) 2024-2025 alkoleft. All rights reserved.
 * This file is part of the mcp-bsl-context project.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for full license information.
 */

package ru.alkoleft.context

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import ru.alkoleft.context.infrastructure.platform.PlatformContextPathResolver
import java.util.concurrent.CountDownLatch

@SpringBootApplication
class McpServerApplication {
    @Bean
    fun stdioKeepAlive(environment: Environment): ApplicationRunner =
        ApplicationRunner {
            if (environment.activeProfiles.contains("stdio")) {
                CountDownLatch(1).await()
            }
        }
}

fun main(args: Array<String>) {
    val parser = ArgParser("mcp-bsl-context")

    val platformPath by parser.option(
        ArgType.String,
        shortName = "p",
        fullName = "platform-path",
        description = "Путь к каталогу платформы 1С или каталогу/файлу shcntx_ru.hbk",
    )
    val platformRoot by parser.option(
        ArgType.String,
        fullName = "platform-root",
        description = "Корневой каталог установок 1С. Если не указан, определяется автоматически",
    )
    val platformVersion by parser.option(
        ArgType.String,
        fullName = "platform-version",
        description = "Версия платформы 1С. Если не указана, выбирается последняя найденная версия",
    )
    val noPlatformCache by parser.option(
        ArgType.Boolean,
        fullName = "no-platform-cache",
        description = "Не копировать shcntx_ru.hbk в локальный cache",
    )
    val verbose by parser.option(
        ArgType.Boolean,
        shortName = "v",
        fullName = "verbose",
        description = "Включить отладочное логирование",
    )
    val mode by parser
        .option(
            ArgType.Choice(listOf("sse", "stdio"), { it }),
            shortName = "m",
            fullName = "mode",
            description = "Режим работы: sse (HTTP Server-Sent Events) или stdio (стандартный ввод/вывод)",
        ).default("stdio")
    val ssePort by parser.option(
        ArgType.Int,
        fullName = "port",
        description = "Порт для SSE сервера (по умолчанию 8080)",
    )

    parser.parse(args)

    val resolvedPlatformContext =
        PlatformContextPathResolver.resolve(
            explicitPath = platformPath,
            explicitRoot = platformRoot,
            explicitVersion = platformVersion,
            cacheEnabled = noPlatformCache != true,
        )
    System.setProperty("platform.context.path", resolvedPlatformContext.toString())

    // Настройка логирования
    if (verbose ?: false) {
        System.setProperty("logging.level.root", "DEBUG")
    }

    // Настройка режима работы
    val activeProfiles = mutableListOf<String>()

    when (mode) {
        "sse" -> {
            activeProfiles.add("sse")
            if (ssePort != null) {
                System.setProperty("server.port", ssePort.toString())
            }
        }

        "stdio" -> {
            activeProfiles.add("stdio")
        }
    }

    runApplication<McpServerApplication>(*args) {
        setDefaultProperties(mapOf("spring.profiles.active" to activeProfiles.joinToString(",")))
    }
}
