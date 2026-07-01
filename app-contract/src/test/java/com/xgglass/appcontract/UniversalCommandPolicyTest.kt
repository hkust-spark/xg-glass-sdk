package com.xgglass.appcontract

import com.xgglass.core.GlassesModel
import org.junit.Test
import kotlin.test.assertSame

class UniversalCommandPolicyTest {
    @Test
    fun `filterCommands leaves commands unchanged for all environments`() {
        // Arrange
        val commands = listOf(command("first"), command("second"))

        // Act
        val rayNeoPhoneCommands = UniversalCommandPolicy.filterCommands(
            HostEnvironment(HostKind.PHONE, GlassesModel.RAYNEO),
            commands,
        )
        val simulatorPhoneCommands = UniversalCommandPolicy.filterCommands(
            HostEnvironment(HostKind.PHONE, GlassesModel.SIMULATOR),
            commands,
        )
        val rayNeoGlassesCommands = UniversalCommandPolicy.filterCommands(
            HostEnvironment(HostKind.GLASSES, GlassesModel.RAYNEO),
            commands,
        )

        // Assert
        assertSame(commands, rayNeoPhoneCommands)
        assertSame(commands, simulatorPhoneCommands)
        assertSame(commands, rayNeoGlassesCommands)
    }

    private fun command(id: String): UniversalCommand = object : UniversalCommand {
        override val id: String = id
        override val title: String = "Command $id"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            return Result.success(Unit)
        }
    }
}
