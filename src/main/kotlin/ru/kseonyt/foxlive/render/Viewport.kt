package ru.kseonyt.foxlive.render

data class Viewport(var x: Int, var y: Int, var width: Int, var height: Int) {
    val aspect: Float get() = if (height == 0) 1f else width.toFloat() / height.toFloat()
}
