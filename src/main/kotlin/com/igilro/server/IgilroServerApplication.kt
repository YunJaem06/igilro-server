package com.igilro.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class IgilroServerApplication

fun main(args: Array<String>) {
    val context = runApplication<IgilroServerApplication>(*args)
    if (context.environment.containsProperty("igilro.graph-import.file") ||
        context.environment.containsProperty("igilro.building-import.file")) context.close()
}
