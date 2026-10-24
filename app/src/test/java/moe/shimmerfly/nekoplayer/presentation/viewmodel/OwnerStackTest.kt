package moe.shimmerfly.nekoplayer.presentation.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnerStackTest {

    private val handovers = mutableListOf<CoroutineScope?>()
    private val stack = OwnerStack<CoroutineScope> { it }

    @Test
    fun `newest owner is current`() {
        val main = owner()
        val external = owner()
        stack.push(main)
        stack.push(external)

        assertSame(external, stack.current)
    }

    @Test
    fun `clearing the current owner hands over to the previous live one`() {
        val main = owner()
        val external = owner()
        stack.push(main)
        stack.push(external)

        external.cancel()
        stack.remove(external) { handovers += it }

        assertEquals(listOf<CoroutineScope?>(main), handovers)
        assertSame(main, stack.current)
    }

    @Test
    fun `clearing an older owner keeps the current one without a handover`() {
        val main = owner()
        val external = owner()
        stack.push(main)
        stack.push(external)

        main.cancel()
        stack.remove(main) { handovers += it }

        assertTrue(handovers.isEmpty())
        assertSame(external, stack.current)
    }

    @Test
    fun `clearing the last owner hands over to nobody`() {
        val main = owner()
        stack.push(main)

        stack.remove(main) { handovers += it }

        assertEquals(listOf<CoroutineScope?>(null), handovers)
        assertNull(stack.current)
        assertTrue(stack.isEmpty)
    }

    @Test
    fun `handover skips owners whose scope was already cancelled`() {
        val main = owner()
        val navigationScoped = owner()
        val external = owner()
        stack.push(main)
        stack.push(navigationScoped)
        stack.push(external)

        navigationScoped.cancel()
        stack.remove(external) { handovers += it }

        assertEquals(listOf<CoroutineScope?>(main), handovers)
    }

    @Test
    fun `re-binding an owner moves it back on top`() {
        val main = owner()
        val external = owner()
        stack.push(main)
        stack.push(external)
        stack.push(main)

        stack.remove(main) { handovers += it }

        assertEquals(listOf<CoroutineScope?>(external), handovers)
    }

    private fun owner(): CoroutineScope = CoroutineScope(Job())
}
