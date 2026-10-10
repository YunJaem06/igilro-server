package com.igilro.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class IgilroServerApplication

fun main(args: Array<String>) {
    runApplication<IgilroServerApplication>(*args)
}
