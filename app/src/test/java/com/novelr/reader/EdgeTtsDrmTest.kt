package com.novelr.reader

import com.novelr.reader.service.edgetts.EdgeTtsDrm
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeTtsDrmTest {
    @Test
    fun verifySecMsGecV2TestVector() {
        val token = EdgeTtsDrm.generateSecMsGecV2(1794365400.0)
        assertEquals(
            "1146E9A9EACBCBCC-324FD23CF295F07CB45F1843605669A7918D9A12E1E4ACC1075BF29B0733A816",
            token
        )
    }
}
