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
        renderPostFxPanel()
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

    private var postFxPanelCollapsed: Boolean = false

    private fun renderPostFxPanel() {
        val r = engine.renderer ?: return
        val p = r.postFxParams
        val sw = engine.window.width.toFloat()
        val sh = engine.window.height.toFloat()
        val width = 230f
        val x = sw - width - 10f
        val y = 10f + 26f * (scenes.size + 2) + 30f
        DebugUi.panel("PostFX", x, y, width) {
            // Preset shortcuts
            if (DebugUi.button("Off"))      p.applyOff()
            if (DebugUi.button("VHS"))      p.applyVHS()
            if (DebugUi.button("CRT"))      p.applyCRT()
            if (DebugUi.button("Dreamy"))   p.applyDreamy()
            DebugUi.separator()
            // Bloom
            p.bloomStrength  = DebugUi.slider("bloom",       p.bloomStrength,  0f, 2f)
            p.bloomThreshold = DebugUi.slider("bloom_thr",   p.bloomThreshold, 0f, 1.5f)
            p.bloomRadius    = DebugUi.slider("bloom_r",     p.bloomRadius,    0f, 12f)
            DebugUi.separator()
            // Blur
            p.blurStrength = DebugUi.slider("blur",   p.blurStrength, 0f, 1f)
            p.blurRadius   = DebugUi.slider("blur_r", p.blurRadius,   0f, 12f)
            DebugUi.separator()
            // VHS
            p.vhsWobble = DebugUi.slider("vhs_wobble", p.vhsWobble, 0f, 1f)
            p.chroma    = DebugUi.slider("chroma",     p.chroma,    0f, 1f)
            p.scanlines = DebugUi.slider("scanlines",  p.scanlines, 0f, 1f)
            p.noise     = DebugUi.slider("noise",      p.noise,     0f, 0.5f)
            DebugUi.separator()
            // Composition
            p.vignette = DebugUi.slider("vignette", p.vignette, 0f, 1f)
            p.exposure = DebugUi.slider("exposure", p.exposure, 0.2f, 2f)
            p.tintR    = DebugUi.slider("tint_R",   p.tintR,    0.5f, 1.5f)
            p.tintG    = DebugUi.slider("tint_G",   p.tintG,    0.5f, 1.5f)
            p.tintB    = DebugUi.slider("tint_B",   p.tintB,    0.5f, 1.5f)
        }
    }
}
