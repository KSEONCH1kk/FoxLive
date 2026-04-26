package ru.kseonyt.foxlive.ecs

import kotlin.reflect.KClass

typealias EntityId = Int

interface Component

abstract class System(val world: World) {
    open fun update(dt: Float) {}
    open fun render(dt: Float) {}
}

class World {
    private var nextId: EntityId = 0
    private val alive = HashSet<EntityId>()
    private val storages = HashMap<KClass<out Component>, HashMap<EntityId, Component>>()
    private val updateSystems = ArrayList<System>()
    private val renderSystems = ArrayList<System>()

    fun create(): EntityId {
        val id = nextId++
        alive += id
        return id
    }

    fun destroy(id: EntityId) {
        if (!alive.remove(id)) return
        storages.values.forEach { it.remove(id) }
    }

    fun entities(): Set<EntityId> = alive

    fun <T : Component> add(id: EntityId, c: T): T {
        val store = storages.getOrPut(c::class) { HashMap() }
        store[id] = c
        return c
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Component> get(id: EntityId, type: KClass<T>): T? =
        storages[type]?.get(id) as T?

    inline fun <reified T : Component> get(id: EntityId): T? = get(id, T::class)

    fun <T : Component> remove(id: EntityId, type: KClass<T>) {
        storages[type]?.remove(id)
    }

    inline fun <reified T : Component> remove(id: EntityId) = remove(id, T::class)

    fun <T : Component> has(id: EntityId, type: KClass<T>): Boolean =
        storages[type]?.containsKey(id) == true

    inline fun <reified T : Component> has(id: EntityId): Boolean = has(id, T::class)

    @Suppress("UNCHECKED_CAST")
    fun <T : Component> all(type: KClass<T>): Sequence<Pair<EntityId, T>> {
        val store = storages[type] ?: return emptySequence()
        return store.asSequence().map { (id, c) -> id to (c as T) }
    }

    inline fun <reified T : Component> all(): Sequence<Pair<EntityId, T>> = all(T::class)

    fun query(vararg types: KClass<out Component>): Sequence<EntityId> {
        if (types.isEmpty()) return alive.asSequence()
        val smallest = types.minByOrNull { storages[it]?.size ?: 0 } ?: return emptySequence()
        val baseStore = storages[smallest] ?: return emptySequence()
        return baseStore.keys.asSequence().filter { id ->
            types.all { t -> storages[t]?.containsKey(id) == true }
        }
    }

    fun addSystem(s: System, render: Boolean = false): System {
        if (render) renderSystems += s else updateSystems += s
        return s
    }

    fun update(dt: Float) { updateSystems.forEach { it.update(dt) } }
    fun render(dt: Float) { renderSystems.forEach { it.render(dt) } }
}
