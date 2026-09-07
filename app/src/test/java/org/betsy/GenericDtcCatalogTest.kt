package org.betsy

import org.betsy.decode.DtcMeaning
import org.betsy.decode.GenericDtcCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericDtcCatalogTest {
    @Test
    fun p0456IsTheEvapVerySmallLeak() {
        assertEquals(
            "EVAP System Leak Detected (very small leak)",
            GenericDtcCatalog.title("P0456"),
        )
        assertNull(DtcMeaning.forWire(0x0456))
    }

    @Test
    fun lookupIsCaseInsensitive() {
        assertEquals(GenericDtcCatalog.title("P0456"), GenericDtcCatalog.title("p0456"))
    }

    @Test
    fun anUnknownCodeStaysUnknown() {
        assertNull(GenericDtcCatalog.title("P1234"))
        assertNull(GenericDtcCatalog.title(""))
    }

    @Test
    fun theCatalogIsTheGenericListNotAHandfulOfRows() {
        assertTrue(GenericDtcCatalog.size >= 9000)
    }

    @Test
    fun authoredHybridTextIsNotTheCatalogTitle() {
        val authored = DtcMeaning.forWire(0x0AA6)
        assertNotNull(authored)
        val catalog = GenericDtcCatalog.title("P0AA6")
        assertNotNull(catalog)
        assertFalse(authored!!.what == catalog)
    }
}
