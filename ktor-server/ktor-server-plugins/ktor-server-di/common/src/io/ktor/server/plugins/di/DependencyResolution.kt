/*
 * Copyright 2014-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.plugins.di

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.SerializableConfigValue
import io.ktor.util.reflect.*
import kotlin.reflect.KProperty

/**
 * Functional interface for generating a fresh `DependencyResolver`.
 */
public fun interface DependencyResolution {
    /**
     * Resolves and creates a new instance of `DependencyResolver` using the provided `DependencyProvider`
     * and `DependencyReflection`.
     *
     * @param provider The `DependencyProvider` instance responsible for managing dependency initializers
     *                 and declarations.
     * @param external Externally-provided dependencies that can be used during the resolution.
     * @param reflection The `DependencyReflection` instance used for reflective creation of dependency
     *                   instances.
     * @return A new instance of `DependencyResolver` configured with the provided arguments
     */
    public fun resolve(
        provider: DependencyProvider,
        external: DependencyMap,
        reflection: DependencyReflection,
    ): DependencyResolver
}

/**
 * A map of object instances.
 */
public interface DependencyMap {
    /**
     * Checks if the given dependency key is present in the dependency map.
     *
     * @param key the unique key that identifies the dependency to check for presence
     * @return true if the dependency identified by the key is present in the map, false otherwise
     */
    public fun contains(key: DependencyKey): Boolean

    /**
     * Retrieves an instance of the dependency associated with the given key from the dependency map.
     *
     * @param key the unique key that identifies the dependency to retrieve
     * @return the instance of the dependency associated with the given key
     * @throws MissingDependencyException if no dependency is associated with the given key
     */
    public fun <T : Any> get(key: DependencyKey): T
}

/**
 * A mutable extension of [DependencyMap] that allows for adding and retrieving dependencies.
 */
public interface MutableDependencyMap : DependencyMap {
    public companion object {
        /**
         * Converts a [DependencyMap] into a [DependencyResolver], combining the functionality of both.
         *
         * @param reflection an instance of [DependencyReflection] that provides the ability to create new instances
         *                   of dependencies using class references and initialization logic.
         * @return a new instance of [DependencyResolver] that combines the map behavior of [DependencyMap] with
         *         the instance creation and reflection capabilities of [DependencyReflection].
         */
        public fun MutableDependencyMap.asResolver(reflection: DependencyReflection): DependencyResolver =
            object : MutableDependencyMap by this, DependencyResolver {
                override val reflection: DependencyReflection
                    get() = reflection
            }
    }

    /**
     * Retrieves the value associated with the specified key if it exists. If the key does not already have an associated
     * value, the result of invoking the [defaultValue] function will be stored and returned as the value for the given key.
     *
     * @param key the dependency key used to look up or store the value.
     * @param defaultValue a lambda function that provides a default value to store and return if the key is not found.
     * @return the value associated with the key, either retrieved from the existing association or newly computed and stored.
     */
    public fun <T : Any> getOrPut(key: DependencyKey, defaultValue: () -> T): T
}

/**
 * Extends [DependencyMap] with reflection, allowing for the automatic injection of types.
 */
public interface DependencyResolver : MutableDependencyMap {
    public val reflection: DependencyReflection
}

/**
 * Basic implementation of [DependencyResolver] using a backing map.
 *
 * The map values are of the `Result` type so that we can ignore exceptions in the case of initialization problems for
 * types that are never referenced.
 */
@Suppress("UNCHECKED_CAST")
public class DependencyMapImpl(
    instances: Map<DependencyKey, Result<Any>>
) : MutableDependencyMap {
    private val map = instances.toMutableMap()

    override fun contains(key: DependencyKey): Boolean =
        map.containsKey(key)

    override fun <T : Any> get(key: DependencyKey): T =
        (map[key] ?: throw MissingDependencyException(key)).getOrThrow() as T

    override fun <T : Any> getOrPut(key: DependencyKey, defaultValue: () -> T): T =
        map.getOrPut(key) { runCatching(defaultValue) }.getOrThrow() as T
}

public operator fun DependencyMap.plus(right: DependencyMap): DependencyMap {
    val left = this
    return object : DependencyMap {
        override fun contains(key: DependencyKey): Boolean =
            left.contains(key) || right.contains(key)

        override fun <T : Any> get(key: DependencyKey): T =
            if (left.contains(key)) {
                left.get(key)
            } else {
                right.get(key)
            }
    }
}

public operator fun DependencyMap.plus(right: MutableDependencyMap): MutableDependencyMap {
    val left = this
    return object : MutableDependencyMap {
        override fun contains(key: DependencyKey): Boolean =
            left.contains(key) || right.contains(key)

        override fun <T : Any> get(key: DependencyKey): T =
            if (left.contains(key)) {
                left.get(key)
            } else {
                right.get(key)
            }

        override fun <T : Any> getOrPut(key: DependencyKey, defaultValue: () -> T): T =
            if (left.contains(key)) {
                left.get(key)
            } else {
                right.getOrPut(key, defaultValue)
            }
    }
}

/**
 * Qualifier for specifying when a dependency key maps to a property in the file configuration.
 */
public data object PropertyQualifier

/**
 * Implementation of [DependencyMap] for referencing items from the server's file configuration.
 */
@Suppress("UNCHECKED_CAST")
public class ConfigurationDependencyMap(
    private val config: ApplicationConfig,
) : DependencyMap {
    override fun contains(key: DependencyKey): Boolean =
        key.qualifier == PropertyQualifier && key.name != null && config.propertyOrNull(key.name) != null

    override fun <T : Any> get(key: DependencyKey): T =
        if (key.qualifier != PropertyQualifier || key.name == null) {
            throw MissingDependencyException(key)
        } else {
            (config.propertyOrNull(key.name) as? SerializableConfigValue)?.getAs(key.type) as? T
                ?: throw MissingDependencyException(key)
        }
}

/**
 * Decorates the dependency resolver with a qualified name for the expected type.
 *
 * Useful with delegation when used like: `val connection by dependencies.named("postgres")`
 */
public fun DependencyResolver.named(key: String) =
    DependencyResolverContext(this, key)

/**
 * Property delegation for [DependencyResolverContext] for use with the `named` shorthand for string qualifiers.
 */
public inline operator fun <reified T> DependencyResolverContext.getValue(thisRef: Any?, property: KProperty<*>): T =
    resolver.resolve(key)

/**
 * Context for property delegation with chaining (i.e., `dependencies.named("foo")`)
 */
public data class DependencyResolverContext(
    val resolver: DependencyResolver,
    val key: String,
)

/**
 * Get the dependency from the map for the key represented by the type (and optionally, with the given name).
 */
public inline fun <reified T> DependencyMap.resolve(key: String? = null): T =
    get(DependencyKey(typeInfo<T>(), key))
