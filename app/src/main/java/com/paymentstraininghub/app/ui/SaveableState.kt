// SaveableState.kt
// Savers for rememberSaveable state that the default registry
// cannot store on its own: enums (stored by name) and sets
// (stored as lists). Each saver covers the MutableState itself:
// rememberSaveable applies a custom saver to the value its init
// lambda returns, which is the state object, not the wrapped
// value. Query, filter, selection, and paging state uses
// rememberSaveable so a rotation keeps the learner where they
// were; loaded content and scroll state stay plain remember,
// per the scroll rule.

package com.paymentstraininghub.app.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver

/**
 * Saver for state holding an enum value, stored by its name. An
 * unknown name (from an older build, or a removed entry)
 * restores to [fallback], which defaults to the first value.
 */
fun <T : Enum<T>> enumSaver(
    values: Array<T>,
    fallback: T = values.first()
): Saver<MutableState<T>, String> =
    Saver(
        save = { it.value.name },
        restore = { name ->
            mutableStateOf(values.firstOrNull { it.name == name } ?: fallback)
        }
    )

/**
 * Saver for state holding a nullable enum value, stored by its
 * name. A null value stores nothing, so restore falls back to
 * the initial value; an unknown name restores to null.
 */
fun <T : Enum<T>> nullableEnumSaver(
    values: Array<T>
): Saver<MutableState<T?>, String> =
    Saver(
        save = { it.value?.name },
        restore = { name ->
            mutableStateOf(values.firstOrNull { it.name == name })
        }
    )

/**
 * Saver for state holding a set, stored as a list. The element
 * type must itself be saveable (String, Int, and the other
 * registry types).
 */
fun <T> setSaver(): Saver<MutableState<Set<T>>, List<T>> =
    Saver(
        save = { it.value.toList() },
        restore = { mutableStateOf(it.toSet()) }
    )
