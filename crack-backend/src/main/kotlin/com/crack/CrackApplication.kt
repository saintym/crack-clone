package com.crack

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CrackApplication

fun main(args: Array<String>) {
    runApplication<CrackApplication>(*args)
}
