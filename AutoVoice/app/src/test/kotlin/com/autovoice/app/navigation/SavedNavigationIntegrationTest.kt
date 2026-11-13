package com.autovoice.app.navigation

import com.autovoice.voicebusiness.navigation.NavigationTarget
import com.autovoice.voicebusiness.navigation.SavedNavigationPlace
import com.autovoice.voicebusiness.navigation.SavedNavigationPlaceStore
import com.autovoice.voicebusiness.navigation.SavedPlaceCommand
import com.autovoice.voicebusiness.navigation.SavedPlaceKind
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SavedNavigationIntegrationTest {
    @Test
    fun duplicateRecognizedReplyDoesNotRepeatAHandledCommand() {
        val handled = mutableListOf<SavedPlaceCommand>()
        val commands = SavedNavigationCommandRouter { _, command ->
            handled += command
            "已打开高德地图"
        }

        assertEquals("已打开高德地图", commands.handle("same-turn", "带我回家"))
        assertEquals("", commands.handle("same-turn", "回家"))
        assertEquals(listOf(SavedPlaceCommand.Navigate(SavedPlaceKind.HOME)), handled)
        assertNull(commands.handle("different-turn", "找公司附近的加油站"))
        assertEquals("已打开高德地图", commands.handle("different-turn", "去公司"))
        assertEquals(SavedPlaceCommand.Navigate(SavedPlaceKind.WORK), handled.last())
    }

    @Test
    fun localAddressSurvivesReopeningAndDeletionPersists(@TempDir root: Path) {
        val storage = LocalSavedNavigationPlaceStorage { root.resolve("app-no-backup").toFile() }
        val home = SavedNavigationPlace(NavigationTarget("上海市测试地址", 31.2, 121.5), isGpsCoordinate = true)
        SavedNavigationPlaceStore(storage).save("device-local", SavedPlaceKind.HOME, home)

        val reopened = SavedNavigationPlaceStore(storage)
        assertEquals(home, reopened.read("device-local", SavedPlaceKind.HOME))
        reopened.delete("device-local", SavedPlaceKind.HOME)
        assertNull(SavedNavigationPlaceStore(storage).read("device-local", SavedPlaceKind.HOME))
    }
}
