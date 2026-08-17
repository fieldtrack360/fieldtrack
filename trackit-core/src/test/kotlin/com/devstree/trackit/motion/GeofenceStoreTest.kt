package com.devstree.trackit.motion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.devstree.trackit.domain.model.GeofenceTransition
import com.devstree.trackit.domain.model.TrackItGeofence
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class GeofenceStoreTest {
    private lateinit var store: GeofenceStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("trackit_geofences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = GeofenceStore(context)
    }

    @Test
    fun registryPersistsMultipleFencesAndReplacesMatchingId() {
        store.put(registration("one", latitude = 1.0))
        store.put(registration("two", latitude = 2.0))
        store.put(registration("one", latitude = 3.0))

        assertThat(store.registrations()).hasSize(2)
        assertThat(store.registration("one")?.geofence?.latitude).isEqualTo(3.0)
        assertThat(store.registration("two")).isNotNull()
    }

    @Test
    fun registryEnforcesNineteenFenceCapacityButAllowsReplacement() {
        repeat(TrackItGeofence.MAX_GEOFENCES) { store.put(registration("fence-$it")) }

        assertThat(store.canAdd("new-fence", isStationaryWakeFence = false)).isFalse()
        assertThat(store.canAdd("fence-0", isStationaryWakeFence = false)).isTrue()
        assertThat(store.canAdd("stationary", isStationaryWakeFence = true)).isTrue()
    }

    @Test
    fun eventsAreNewestFirstFilteredPagedAndDeletable() {
        val one = registration("one")
        val two = registration("two")
        store.record(one, GeofenceTransition.ENTER, timestampMs = 100)
        store.record(two, GeofenceTransition.EXIT, timestampMs = 200)
        store.record(one, GeofenceTransition.EXIT, timestampMs = 300)

        val page = store.events(
            geofenceId = "one",
            fromMs = 100,
            toMs = 300,
            limit = 1,
            offset = 0,
        )
        assertThat(page).hasSize(1)
        assertThat(page.single().timestampMs).isEqualTo(300)
        assertThat(page.single().eventName).isEqualTo("exit-one")

        assertThat(store.deleteEvents("one", fromMs = null, toMs = 150)).isEqualTo(1)
        assertThat(store.events(null, null, null, 10, 0).map { it.timestampMs })
            .containsExactly(300L, 200L)
            .inOrder()
    }

    private fun registration(id: String, latitude: Double = 0.0): RegisteredGeofence =
        RegisteredGeofence(
            geofence = TrackItGeofence(
                id = id,
                latitude = latitude,
                longitude = 0.0,
                radiusM = 100f,
                onEnterEvent = "enter-$id",
                onExitEvent = "exit-$id",
            ),
            isStationaryWakeFence = false,
        )
}
