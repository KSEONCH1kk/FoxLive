package ru.kseonyt.foxlive.core

import ru.kseonyt.foxlive.ecs.World
import ru.kseonyt.foxlive.render.Camera
import ru.kseonyt.foxlive.render.VulkanRenderer

class Engine(title: String, width: Int, height: Int, val enableValidation: Boolean = false) {
    val window = Window(title, width, height)
    var renderer: VulkanRenderer? = null
        private set
    val world = World()
    val camera = Camera()
    val scenes = SceneManager(this)

    fun init() {
        window.init()
        val r = VulkanRenderer(window, enableValidation)
        renderer = r
        r.init()
        window.show()
    }

    fun run(onUpdate: (Float) -> Unit = {}) {
        val r = renderer ?: error("Engine.run called before init")
        while (!window.shouldClose()) {
            Time.tick()
            window.pollEvents()
            r.beginUiFrame()
            onUpdate(Time.delta)
            world.update(Time.delta)    // always-on systems (Tween, Particle, Physics)
            scenes.update(Time.delta)   // current scene + scene-switcher UI
            r.drawFrame(world, camera)
            world.render(Time.delta)
        }
        r.waitIdle()
    }

    fun shutdown() {
        scenes.teardownAll()
        renderer?.destroy()
        renderer = null
        window.destroy()
    }
}
