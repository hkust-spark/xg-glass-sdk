package com.xgglass.appcontract

import com.xgglass.core.GlassesModel
import kotlin.test.Test
import kotlin.test.assertSame

class UniversalAppEntrySimpleTest {
    @Test
    fun `commands with environment delegates to parameterless commands`() {
        // Arrange
        val commands = listOf(command("tts"))
        val entry = object : UniversalAppEntrySimple {
            override val id: String = "simple_entry"
            override val displayName: String = "Simple Entry"

            override fun commands(): List<UniversalCommand> = commands
        }
        val env = HostEnvironment(HostKind.PHONE, GlassesModel.SIMULATOR)

        // Act
        val delegatedCommands = entry.commands(env)

        // Assert
        assertSame(commands, delegatedCommands)
    }

    private fun command(id: String): UniversalCommand = object : UniversalCommand {
        override val id: String = id
        override val title: String = "Command $id"

        override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
            return Result.success(Unit)
        }
    }
}
