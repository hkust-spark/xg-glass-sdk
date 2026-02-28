package com.example.xgglassapp.logic

import com.universalglasses.appcontract.UniversalAppEntrySimple
import com.universalglasses.appcontract.UniversalCommand

/**
 * Example entry (ExampleAppEntry)
 *
 * IMPORTANT: Rename/replace this file.
 *
 * - This is a compilable, runnable starter entry so `xg-glass init` projects work out of the box.
 * - In real apps, you should rename this file/class (e.g. `MyAppEntry.kt`) and update `entryClass`
 *   in the project root `xg-glass.yaml`.
 * - The host app loads this class via reflection using the manifest meta-data key
 *   `com.universalglasses.app_entry_class`.
 */
class ExampleAppEntry : UniversalAppEntrySimple {
    override val id: String = "example_app"
    override val displayName: String = "Example XgGlass App"

    override fun commands(): List<UniversalCommand> {
        // Return the list of user-facing actions (buttons/menu items/gesture commands).
        // Start minimal: no commands by default.
        //
        // To add a command, return something like:
        //   return listOf(object : UniversalCommand { ... })
        return emptyList()
    }
}
