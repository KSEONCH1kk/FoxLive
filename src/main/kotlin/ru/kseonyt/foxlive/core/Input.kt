package ru.kseonyt.foxlive.core

import org.lwjgl.glfw.GLFW.*

class Input {
    private val held = HashSet<Int>()
    private val pressed = HashSet<Int>()
    private val mouseHeld = HashSet<Int>()
    private val mousePressed = HashSet<Int>()
    private val mouseReleased = HashSet<Int>()

    var mouseX: Double = 0.0; private set
    var mouseY: Double = 0.0; private set
    var deltaX: Double = 0.0; private set
    var deltaY: Double = 0.0; private set
    private var lastX: Double = 0.0
    private var lastY: Double = 0.0
    private var firstMouse = true

    fun onKey(key: Int, action: Int) {
        when (action) {
            GLFW_PRESS -> { held += key; pressed += key }
            GLFW_RELEASE -> held -= key
        }
    }

    fun onMouseButton(button: Int, action: Int) {
        when (action) {
            GLFW_PRESS -> { mouseHeld += button; mousePressed += button }
            GLFW_RELEASE -> { mouseHeld -= button; mouseReleased += button }
        }
    }

    fun onMouse(x: Double, y: Double) {
        if (firstMouse) { lastX = x; lastY = y; firstMouse = false }
        deltaX += x - lastX
        deltaY += y - lastY
        lastX = x; lastY = y
        mouseX = x; mouseY = y
    }

    fun beginFrame() {
        pressed.clear()
        mousePressed.clear()
        mouseReleased.clear()
        deltaX = 0.0; deltaY = 0.0
    }

    fun isDown(key: Int): Boolean = key in held
    fun wasPressed(key: Int): Boolean = key in pressed

    fun isMouseDown(button: Int = GLFW_MOUSE_BUTTON_LEFT): Boolean = button in mouseHeld
    fun wasMousePressed(button: Int = GLFW_MOUSE_BUTTON_LEFT): Boolean = button in mousePressed
    fun wasMouseReleased(button: Int = GLFW_MOUSE_BUTTON_LEFT): Boolean = button in mouseReleased
}
