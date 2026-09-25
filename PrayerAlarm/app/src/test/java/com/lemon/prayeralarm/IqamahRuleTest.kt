package com.lemon.prayeralarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class IqamahRuleTest {

    private val autumnIsha = LocalDateTime.of(2026, 9, 24, 20, 10)
    private val summerIsha = LocalDateTime.of(2026, 6, 21, 22, 40)

    @Test
    fun `nothing set gives no iqamah`() {
        assertNull(IqamahRule.NONE.resolve(autumnIsha))
    }

    @Test
    fun `minutes only follow the adhan`() {
        val rule = IqamahRule(null, 5)
        assertEquals(LocalDateTime.of(2026, 9, 24, 20, 15), rule.resolve(autumnIsha))
    }

    @Test
    fun `fixed time only is used as given`() {
        val rule = IqamahRule(LocalTime.of(20, 30), null)
        assertEquals(LocalDateTime.of(2026, 9, 24, 20, 30), rule.resolve(autumnIsha))
    }

    @Test
    fun `with both, the fixed time holds while the adhan is early`() {
        val rule = IqamahRule(LocalTime.of(20, 30), 5)
        assertEquals(LocalDateTime.of(2026, 9, 24, 20, 30), rule.resolve(autumnIsha))
    }

    @Test
    fun `with both, the adhan takes over once it passes the fixed time`() {
        val rule = IqamahRule(LocalTime.of(20, 30), 5)
        assertEquals(LocalDateTime.of(2026, 6, 21, 22, 45), rule.resolve(summerIsha))
    }

    @Test
    fun `a fixed time the adhan has passed never puts iqamah before the adhan`() {
        val rule = IqamahRule(LocalTime.of(20, 30), null)
        assertEquals(summerIsha, rule.resolve(summerIsha))
    }
}
