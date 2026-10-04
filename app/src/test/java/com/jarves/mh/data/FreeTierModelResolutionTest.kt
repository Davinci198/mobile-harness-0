package com.jarves.mh.data

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.network.DiscoveredModel
import com.jarves.mh.network.EndpointModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeTierModelResolutionTest {
    private fun freeCatalog(vararg models: DiscoveredModel) = EndpointModelCatalog(
        kindName = ProviderKind.FREE.name,
        baseUrl = ProviderKind.FREE.defaultBaseUrl,
        models = models.toList(),
    )

    private fun model(id: String, isFree: Boolean, health: String?) =
        DiscoveredModel(id = id, isFree = isFree, health = health)

    @Test
    fun brokenStoredModelFallsBackToTheFirstHealthyFreeModel() {
        val stored = ProviderProfile(kind = ProviderKind.FREE, model = "stepfun/step-3.7-flash:free")
        val catalogs = listOf(
            freeCatalog(
                model("some/paid-model", isFree = false, health = "OK"),
                model("inclusionai/ling-3.0-flash-sante:free", isFree = true, health = "OK"),
                model("stepfun/step-3.7-flash:free", isFree = true, health = "FAIL"),
            ),
        )

        val resolved = applyFreeTierModel(stored, catalogs)

        assertEquals("inclusionai/ling-3.0-flash-sante:free", resolved.model)
    }

    @Test
    fun healthyStoredModelIsKept() {
        val stored = ProviderProfile(kind = ProviderKind.FREE, model = "inclusionai/ling-3.0-flash-sante:free")
        val catalogs = listOf(
            freeCatalog(
                model("inclusionai/ling-3.0-flash-sante:free", isFree = true, health = "OK"),
                model("stepfun/step-3.7-flash:free", isFree = true, health = "FAIL"),
            ),
        )

        assertEquals(stored.model, applyFreeTierModel(stored, catalogs).model)
    }

    @Test
    fun storedModelMissingFromTheCatalogIsReplaced() {
        val stored = ProviderProfile(kind = ProviderKind.FREE, model = "deepseek-ai/DeepSeek-V4-Flash-0731")
        val catalogs = listOf(
            freeCatalog(model("inclusionai/ling-3.1-flash", isFree = true, health = "OK")),
        )

        val resolved = applyFreeTierModel(stored, catalogs)

        assertEquals("inclusionai/ling-3.1-flash", resolved.model)
    }

    @Test
    fun profileWithoutACatalogKeepsItsModel() {
        val stored = ProviderProfile(kind = ProviderKind.FREE, model = "stepfun/step-3.7-flash:free")

        assertEquals(stored.model, applyFreeTierModel(stored, emptyList()).model)
    }

    @Test
    fun nonFreeProfileIsUntouched() {
        val stored = ProviderProfile(kind = ProviderKind.CUSTOM, model = "minimax/MiniMax-M2")
        val catalogs = listOf(
            freeCatalog(model("inclusionai/ling-3.1-flash", isFree = true, health = "OK")),
        )

        assertEquals(stored.model, applyFreeTierModel(stored, catalogs).model)
    }

    @Test
    fun noHealthyFreeModelKeepsTheStoredChoice() {
        val stored = ProviderProfile(kind = ProviderKind.FREE, model = "stepfun/step-3.7-flash:free")
        val catalogs = listOf(
            freeCatalog(
                model("stepfun/step-3.7-flash:free", isFree = true, health = "FAIL"),
                model("some/paid-model", isFree = false, health = "FAIL"),
            ),
        )

        assertEquals(stored.model, applyFreeTierModel(stored, catalogs).model)
    }

    @Test
    fun catalogWithOnlyPaidEntriesKeepsTheStoredChoice() {
        val stored = ProviderProfile(kind = ProviderKind.FREE, model = "stepfun/step-3.7-flash:free")
        val catalogs = listOf(
            freeCatalog(
                model("some/paid-model", isFree = false, health = "OK"),
                model("other/paid-model", isFree = false, health = "FAIL"),
            ),
        )

        assertEquals(stored.model, applyFreeTierModel(stored, catalogs).model)
    }
}
