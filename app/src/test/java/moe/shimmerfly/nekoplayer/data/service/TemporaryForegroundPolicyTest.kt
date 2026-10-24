package moe.shimmerfly.nekoplayer.data.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TemporaryForegroundPolicyTest {

    @Test
    fun plainWidgetStart_doesNotRequireTemporaryForeground() {
        val required = requiresTemporaryForegroundForStart(
            forcedForegroundStart = false,
            pendingTemporaryForegroundStart = false,
            isMediaButtonIntent = false,
            startedTemporaryForegroundInOnCreate = false,
            serviceAlreadyForeground = false
        )

        assertThat(required).isFalse()
    }

    @Test
    fun plainWidgetStartWhilePlaying_doesNotRequireTemporaryForeground() {
        val required = requiresTemporaryForegroundForStart(
            forcedForegroundStart = false,
            pendingTemporaryForegroundStart = false,
            isMediaButtonIntent = false,
            startedTemporaryForegroundInOnCreate = false,
            serviceAlreadyForeground = true
        )

        assertThat(required).isFalse()
    }

    @Test
    fun forcedForegroundExtra_requiresTemporaryForeground() {
        val required = requiresTemporaryForegroundForStart(
            forcedForegroundStart = true,
            pendingTemporaryForegroundStart = false,
            isMediaButtonIntent = false,
            startedTemporaryForegroundInOnCreate = false,
            serviceAlreadyForeground = false
        )

        assertThat(required).isTrue()
    }

    @Test
    fun forcedForegroundExtraAlreadyPromotedInOnCreate_stillRequiresTemporaryForeground() {
        val required = requiresTemporaryForegroundForStart(
            forcedForegroundStart = true,
            pendingTemporaryForegroundStart = false,
            isMediaButtonIntent = false,
            startedTemporaryForegroundInOnCreate = true,
            serviceAlreadyForeground = false
        )

        assertThat(required).isTrue()
    }

    @Test
    fun pendingMarkedStart_requiresTemporaryForeground() {
        val required = requiresTemporaryForegroundForStart(
            forcedForegroundStart = false,
            pendingTemporaryForegroundStart = true,
            isMediaButtonIntent = false,
            startedTemporaryForegroundInOnCreate = false,
            serviceAlreadyForeground = false
        )

        assertThat(required).isTrue()
    }

    @Test
    fun mediaButtonWhileNotForeground_requiresTemporaryForeground() {
        val required = requiresTemporaryForegroundForStart(
            forcedForegroundStart = false,
            pendingTemporaryForegroundStart = false,
            isMediaButtonIntent = true,
            startedTemporaryForegroundInOnCreate = false,
            serviceAlreadyForeground = false
        )

        assertThat(required).isTrue()
    }

    @Test
    fun mediaButtonWhileAlreadyForeground_doesNotRequireTemporaryForeground() {
        val required = requiresTemporaryForegroundForStart(
            forcedForegroundStart = false,
            pendingTemporaryForegroundStart = false,
            isMediaButtonIntent = true,
            startedTemporaryForegroundInOnCreate = false,
            serviceAlreadyForeground = true
        )

        assertThat(required).isFalse()
    }

    @Test
    fun mediaButtonAlreadyPromotedInOnCreate_doesNotRequireTemporaryForeground() {
        val required = requiresTemporaryForegroundForStart(
            forcedForegroundStart = false,
            pendingTemporaryForegroundStart = false,
            isMediaButtonIntent = true,
            startedTemporaryForegroundInOnCreate = true,
            serviceAlreadyForeground = false
        )

        assertThat(required).isFalse()
    }
}
