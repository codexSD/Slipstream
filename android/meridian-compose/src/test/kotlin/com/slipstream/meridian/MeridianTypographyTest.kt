package com.slipstream.meridian

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeridianTypographyTest {

    @Test
    fun `numeric styles request tabular figures`() {
        // Without tnum a live MB/s readout visibly jitters as digits change width.
        listOf(
            "heroMetric" to MeridianText.heroMetric,
            "itemTitle" to MeridianText.itemTitle,
            "label" to MeridianText.label,
            "labelBold" to MeridianText.labelBold,
        ).forEach { (name, style) ->
            assertEquals("$name must request tabular figures", "tnum", style.fontFeatureSettings)
        }
    }

    @Test
    fun `the hero metric is 40sp bold`() {
        assertEquals(40.sp, MeridianText.heroMetric.fontSize)
        assertEquals(FontWeight.Bold, MeridianText.heroMetric.fontWeight)
    }

    @Test
    fun `the scale matches the specification`() {
        assertEquals(20.sp, MeridianText.screenTitle.fontSize)
        assertEquals(15.sp, MeridianText.itemTitle.fontSize)
        assertEquals(14.sp, MeridianText.body.fontSize)
        assertEquals(12.sp, MeridianText.label.fontSize)
        assertEquals(11.sp, MeridianText.micro.fontSize)
    }

    @Test
    fun `titles are bold and body is regular`() {
        assertEquals(FontWeight.Bold, MeridianText.screenTitle.fontWeight)
        assertEquals(FontWeight.Bold, MeridianText.itemTitle.fontWeight)
        assertEquals(FontWeight.Normal, MeridianText.body.fontWeight)
    }

    @Test
    fun `no style applies letter spacing beyond the default`() {
        listOf(MeridianText.body, MeridianText.itemTitle, MeridianText.screenTitle).forEach {
            assertTrue(it.letterSpacing.isUnspecified || it.letterSpacing.value == 0f)
        }
    }

    @Test
    fun `radius steps match the specification`() {
        assertEquals(12.dp, MeridianRadius.sm)
        assertEquals(14.dp, MeridianRadius.md)
        assertEquals(16.dp, MeridianRadius.lg)
        assertEquals(RoundedCornerShape(50), MeridianRadius.pill)
    }

    @Test
    fun `nothing is sharper than the sm step`() {
        val shapes = meridianShapes()
        listOf(shapes.extraSmall, shapes.small, shapes.medium, shapes.large, shapes.extraLarge)
            .forEach { assertTrue("Zero radius is never used in Meridian", it != RoundedCornerShape(0.dp)) }
    }

    @Test
    fun `spacing follows the 4pt grid`() {
        listOf(
            MeridianSpacing.xs, MeridianSpacing.sm, MeridianSpacing.md,
            MeridianSpacing.lg, MeridianSpacing.xl, MeridianSpacing.xxl,
        ).forEach {
            assertEquals("${it.value}dp is off the 4pt grid", 0f, it.value % 4f, 0.001f)
        }
    }

    @Test
    fun `semantic spacing matches the specification`() {
        assertEquals(16.dp, MeridianSpacing.screen)
        assertEquals(12.dp, MeridianSpacing.cardInner)
        assertEquals(20.dp, MeridianSpacing.section)
    }
}
