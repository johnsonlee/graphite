package io.johnsonlee.graphite.c4.render

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.c4.C4Model

object JsonRenderer {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun render(model: C4Model): String = gson.toJson(model)
}
