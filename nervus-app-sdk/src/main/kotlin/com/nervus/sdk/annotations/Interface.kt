package com.nervus.sdk.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Interface(val id: String = "")
