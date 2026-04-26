package ru.kseonyt

import org.lwjgl.system.Configuration
import ru.kseonyt.foxlive.core.Engine
import ru.kseonyt.foxlive.game.ParticleSystem
import ru.kseonyt.foxlive.game.TweenSystem
import ru.kseonyt.foxlive.game.scenes.CardScene
import ru.kseonyt.foxlive.game.scenes.PhysicsScene
import ru.kseonyt.foxlive.game.scenes.SnakeScene
import ru.kseonyt.foxlive.game.scenes.WaterScene
import ru.kseonyt.foxlive.physics.PhysicsSystem

fun main() {
    // LWJGL stack defaults to 64KB; VkInstance/VkDevice constructors enumerate
    // driver extensions on the same stack, which can overflow on Windows.
    Configuration.STACK_SIZE.set(2048)

    println("FoxLive 3D Engine — Vulkan + LWJGL")
    println("Click a scene name in the top-left panel to switch.")

    val engine = Engine("FoxLive", 1280, 720, enableValidation = false)
    var initFailed = false
    try {
        try {
            engine.init()
        } catch (t: Throwable) {
            initFailed = true
            System.err.println("[FoxLive] Initialization failed:")
            t.printStackTrace()
            return
        }

        // Always-on systems
        engine.world.addSystem(TweenSystem(engine.world))
        engine.world.addSystem(ParticleSystem(engine.world))
        val physics = PhysicsSystem(engine.world)
        engine.world.addSystem(physics)

        // Register scenes — first one becomes active. CardScene draws cards procedurally
        // (no atlas needed); a baked atlas can still be passed if you want pre-rendered art.
        engine.scenes.register(SnakeScene(engine))
        engine.scenes.register(PhysicsScene(engine, physics))
        engine.scenes.register(WaterScene(engine))
        engine.scenes.register(CardScene(engine))

        engine.run()
    } finally {
        try { engine.shutdown() } catch (t: Throwable) {
            if (!initFailed) System.err.println("[FoxLive] Shutdown error: ${t.message}")
        }
    }
}
