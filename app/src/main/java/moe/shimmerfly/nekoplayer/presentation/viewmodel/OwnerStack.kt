package moe.shimmerfly.nekoplayer.presentation.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

/**
 * The live PlayerViewModel bindings of an app-singleton state holder, oldest first.
 *
 * MainActivity and ExternalPlayerActivity each own a PlayerViewModel, and both bind the same
 * singleton holders to their viewModelScope. The newest binding drives the holder. When that
 * PlayerViewModel is cleared while an older one is still alive (ExternalPlayerActivity finishing
 * over a backgrounded MainActivity), the holder has to fall back to the older binding: going
 * scope-less instead would leave the surviving PlayerViewModel without sorting, album colours,
 * the sleep timer or seek-bar progress until the process restarts.
 *
 * Call it from the main thread only, like ViewModel init and onCleared.
 */
internal class OwnerStack<B : Any>(private val scopeOf: (B) -> CoroutineScope) {
    private val bindings = mutableListOf<B>()

    /** The binding that drives the holder, or null when no PlayerViewModel is bound. */
    val current: B? get() = bindings.lastOrNull()

    val isEmpty: Boolean get() = bindings.isEmpty()

    /** Makes [binding] current, replacing an earlier binding of the same scope. */
    fun push(binding: B) {
        val owner = scopeOf(binding)
        bindings.removeAll { scopeOf(it) === owner }
        bindings += binding
    }

    /**
     * Drops [owner]'s binding, along with any whose scope has already been cancelled. When that
     * changes the current binding, [onHandover] receives the binding that takes over, or null
     * when no live one is left. Dropping an older binding leaves the current one in place.
     */
    fun remove(owner: CoroutineScope, onHandover: (B?) -> Unit) {
        val previous = current
        bindings.removeAll { scopeOf(it) === owner || !scopeOf(it).isActive }
        val next = current
        if (next !== previous) onHandover(next)
    }
}
