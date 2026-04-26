package ru.kseonyt.foxlive.core

import ru.kseonyt.foxlive.ecs.EntityId
import ru.kseonyt.foxlive.ecs.World
import ru.kseonyt.foxlive.ui.DebugUi

abstract class Scene(val engine: Engine) {
    abstract val name: String
    val world: World get() = engine.world

    /** Entities owned by this scene — destroyed on teardown. */
    protected val ownedEntities: MutableSet<EntityId> = HashSet()

    open fun setup() {}
    open fun teardown() {
        for (id in ownedEntities.toList()) world.destroy(id)
        ownedEntities.clear()
    }
    abstract fun update(dt: Float)

    /** Helper: create an entity tracked by this scene. */
    protected fun spawn(): EntityId {
        val id = world.create()
        ownedEntities += id
        return id
    }
    /** Mark an externally-created entity as owned by this scene. */
    protected fun own(id: EntityId): EntityId {
        ownedEntities += id
        return id
    }
}

class SceneManager(val engine: Engine) {
    private val scenes = LinkedHashMap<String, Scene>()
    var current: Scene? = null
        private set
    private var pending: String? = null

    fun register(scene: Scene) {
        scenes[scene.name] = scene
        if (current == null) {
            current = scene
            scene.setup()
        }
    }

    /** Schedule a switch — applied at the start of next update so the running frame finishes cleanly. */
    fun switchTo(name: String) {
        if (scenes[name] == null) error("Unknown scene: $name")
        pending = name
    }

    fun update(dt: Float) {
        pending?.let { name ->
            val next = scenes[name]!!
            current?.teardown()
            current = next
            next.setup()
            pending = null
        }
        current?.update(dt)
        renderSwitcher()
    }

    fun teardownAll() {
        current?.teardown()
        current = null
    }

    private fun renderSwitcher() {
        val sw = engine.window.width.toFloat()
        DebugUi.panel("FoxLive", sw - 200f, 10f, 190f) {
            for ((name, _) in scenes) {
                val label = if (current?.name == name) "▶ $name" else "  $name"
                if (DebugUi.button(label) && current?.name != name) switchTo(name)
            }
        }
    }
}
