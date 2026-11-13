package com.autovoice.voicebusiness.navigation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class SavedNavigationPlaceStoreTest {
    private val disk = MemoryStorage()
    private var profile = "driver-a"
    private val store = SavedNavigationPlaceStore(disk)
    private val places = SavedNavigationPlaces(store) { profile }
    private val home = SavedNavigationPlace(NavigationTarget("私密家庭地址", 31.2, 121.5), isGpsCoordinate = true)
    private val work = SavedNavigationPlace(NavigationTarget("私密公司地址", 31.3, 121.6))

    @Test fun unsetDoesNotResolveToAnyDestination() {
        assertNull(places.read(SavedPlaceKind.HOME))
    }

    @Test fun cancelOverwriteKeepsTheOldValue() {
        val first = assertInstanceOf(SavedPlaceProposal.Ready::class.java, places.prepareSave(SavedPlaceKind.HOME, home)).edit
        assertEquals(SavedPlaceCommit.SAVED, places.confirm(first))
        val overwrite = assertInstanceOf(SavedPlaceProposal.Ready::class.java, places.prepareSave(SavedPlaceKind.HOME, work)).edit
        assertEquals(SavedPlaceEdit.Action.REPLACE, overwrite.action)
        places.cancel(overwrite)
        assertEquals(SavedPlaceCommit.EXPIRED, places.confirm(overwrite))
        assertEquals(home, places.read(SavedPlaceKind.HOME))
    }

    @Test fun confirmOverwriteReplacesTheOldValueExactlyOnce() {
        store.save(profile, SavedPlaceKind.HOME, home)
        val overwrite = assertInstanceOf(SavedPlaceProposal.Ready::class.java, places.prepareSave(SavedPlaceKind.HOME, work)).edit
        assertEquals(SavedPlaceCommit.SAVED, places.confirm(overwrite))
        assertEquals(SavedPlaceCommit.EXPIRED, places.confirm(overwrite))
        assertEquals(work, places.read(SavedPlaceKind.HOME))
    }

    @Test fun deletedHomeNeverResolvesFromACache() {
        store.save(profile, SavedPlaceKind.HOME, home)
        assertEquals(home, places.read(SavedPlaceKind.HOME))
        val removal = assertInstanceOf(SavedPlaceProposal.Ready::class.java, places.prepareDelete(SavedPlaceKind.HOME)).edit
        assertEquals(SavedPlaceCommit.DELETED, places.confirm(removal))
        assertNull(places.read(SavedPlaceKind.HOME))
        assertNull(SavedNavigationPlaceStore(disk).read(profile, SavedPlaceKind.HOME))
    }

    @Test fun changingProfilesUsesTheirOwnAddressAndRejectsPendingConfirmation() {
        store.save(profile, SavedPlaceKind.HOME, home)
        val edit = assertInstanceOf(SavedPlaceProposal.Ready::class.java, places.prepareDelete(SavedPlaceKind.HOME)).edit
        profile = "driver-b"
        assertNull(places.read(SavedPlaceKind.HOME))
        store.save(profile, SavedPlaceKind.HOME, work)
        assertEquals(work, places.read(SavedPlaceKind.HOME))
        assertEquals(SavedPlaceCommit.EXPIRED, places.confirm(edit))
        profile = "driver-a"
        assertEquals(home, places.read(SavedPlaceKind.HOME))
    }

    @Test fun homeNavigationHandsOffOnlyTheCurrentSavedPointAndStopsAfterDeletion() {
        val opened = mutableListOf<String>()
        val manager = AppDialogueManager(
            interactionIdProvider = { "interaction:$it" },
            onNavigation = {},
            launchNavigation = { uri -> opened += uri; true },
        )

        assertTrue(places.navigate(SavedPlaceKind.HOME, "unset", manager).contains("常用地址"))
        assertTrue(opened.isEmpty())

        store.save(profile, SavedPlaceKind.HOME, home)
        places.navigate(SavedPlaceKind.HOME, "saved", manager)
        val uri = opened.single()
        assertTrue(uri.startsWith("androidamap://navi?"))
        assertTrue(uri.contains("lat=31.2&lon=121.5&dev=1"))
        assertFalse(uri.contains("私密"))
        assertEquals("家庭地址", manager.navigationSession.snapshot.trip?.destination?.name)
        assertEquals(NavigationHandoff.ACCEPTED, manager.navigationSession.snapshot.handoff)

        val removal = assertInstanceOf(SavedPlaceProposal.Ready::class.java, places.prepareDelete(SavedPlaceKind.HOME)).edit
        assertEquals(SavedPlaceCommit.DELETED, places.confirm(removal))
        assertTrue(places.navigate(SavedPlaceKind.HOME, "after-delete", manager).contains("常用地址"))
        assertEquals(1, opened.size)
    }

    @Test fun confirmationCannotReplaceAnAddressChangedSinceItWasShown() {
        store.save(profile, SavedPlaceKind.HOME, home)
        val removal = assertInstanceOf(SavedPlaceProposal.Ready::class.java, places.prepareDelete(SavedPlaceKind.HOME)).edit
        store.save(profile, SavedPlaceKind.HOME, work)
        assertEquals(SavedPlaceCommit.EXPIRED, places.confirm(removal))
        assertEquals(work, places.read(SavedPlaceKind.HOME))
    }

    @Test fun restartedStoreRestoresTheAddressAndCoordinateSystem() {
        store.save(profile, SavedPlaceKind.HOME, home)
        store.save(profile, SavedPlaceKind.WORK, work)
        val restarted = SavedNavigationPlaceStore(disk)
        assertEquals(home, restarted.read(profile, SavedPlaceKind.HOME))
        assertEquals(work, restarted.read(profile, SavedPlaceKind.WORK))
    }

    @Test fun unresolvedLocationCannotBecomeAPendingSave() {
        assertEquals(SavedPlaceProposal.UNKNOWN_LOCATION, places.prepareSave(SavedPlaceKind.HOME, null))
        assertNull(places.read(SavedPlaceKind.HOME))
    }

    @Test fun failedWritePreservesOldValueAndFailureDoesNotRevealAddress() {
        store.save(profile, SavedPlaceKind.HOME, home)
        val edit = assertInstanceOf(SavedPlaceProposal.Ready::class.java, places.prepareSave(SavedPlaceKind.HOME, work)).edit
        disk.failWrite = true
        assertEquals(SavedPlaceCommit.STORAGE_UNAVAILABLE, places.confirm(edit))
        assertEquals(home, places.read(SavedPlaceKind.HOME))
        assertFalse(edit.toString().contains(work.target.name))
        assertFalse(edit.toString().contains(work.target.latitude.toString()))
    }

    @Test fun failedReadHasASanitizedErrorAndDoesNotPretendAddressIsUnset() {
        disk.failRead = true
        val error = assertThrows(SavedNavigationPlaceStore.StorageUnavailable::class.java) {
            places.read(SavedPlaceKind.HOME)
        }
        assertFalse(error.stackTraceToString().contains(home.target.name))
        assertEquals(SavedPlaceProposal.STORAGE_UNAVAILABLE, places.prepareDelete(SavedPlaceKind.HOME))
    }

    @Test fun rawStorageKeysAndPlaceDiagnosticsOmitProfileAndAddress() {
        store.save(profile, SavedPlaceKind.HOME, home)
        assertTrue(disk.values.keys.none { it.contains(profile) })
        assertFalse(home.toString().contains(home.target.name))
        assertFalse(home.toString().contains(home.target.longitude.toString()))
    }

    @Test fun matchesExplicitSavedPlaceCommandsWithoutStealingOtherNavigation() {
        assertEquals(SavedPlaceCommand.Navigate(SavedPlaceKind.HOME), SavedPlaceCommand.parse("请带我回家！"))
        assertEquals(SavedPlaceCommand.Navigate(SavedPlaceKind.WORK), SavedPlaceCommand.parse("去公司"))
        assertEquals(SavedPlaceCommand.UseCurrentLocation(SavedPlaceKind.HOME), SavedPlaceCommand.parse("把这里设为家"))
        assertEquals(SavedPlaceCommand.UseCurrentLocation(SavedPlaceKind.WORK), SavedPlaceCommand.parse("公司换到这里了"))
        assertEquals(SavedPlaceCommand.Delete(SavedPlaceKind.HOME), SavedPlaceCommand.parse("删除我的家庭地址"))
        assertNull(SavedPlaceCommand.parse("不要带我回家"))
        assertNull(SavedPlaceCommand.parse("去公司附近的餐馆"))
        assertNull(SavedPlaceCommand.parse("先回家再去公司"))
    }

    private inner class MemoryStorage : SavedNavigationPlaceStore.Storage {
        val values = mutableMapOf<String, String>()
        var failRead = false
        var failWrite = false

        override fun read(key: String): String? {
            if (failRead) error("bad value: 私密家庭地址")
            return values[key]
        }

        override fun write(key: String, value: String?) {
            if (failWrite) error("cannot write: 私密公司地址")
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
