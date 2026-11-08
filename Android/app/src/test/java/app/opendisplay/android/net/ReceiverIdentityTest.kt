package app.opendisplay.android.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverIdentityTest {
    @Test fun comparisonCodeMatchesMacAndIsOrderIndependent() {
        val mac = "A1".repeat(32)
        val tv = "B2".repeat(32)

        assertEquals("03A2-C33F-1F88", ReceiverIdentity.comparisonCode(mac, tv))
        assertEquals(ReceiverIdentity.comparisonCode(mac, tv), ReceiverIdentity.comparisonCode(tv, mac))
    }
}
