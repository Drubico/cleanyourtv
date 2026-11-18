package com.gathin.tvhome;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelHomeTest {
    @Test public void sixButtonsMirrorLeftAndRightShoulders() {
        assertEquals(0, ChannelHome.buttonIndexForColumn(0, 4, 6));
        assertEquals(2, ChannelHome.buttonIndexForColumn(1, 4, 6));
        assertEquals(3, ChannelHome.buttonIndexForColumn(2, 4, 6));
        assertEquals(5, ChannelHome.buttonIndexForColumn(3, 4, 6));
    }

    @Test public void fiveButtonsLandOnProfileStoreCleanAndSettings() {
        assertEquals(0, ChannelHome.buttonIndexForColumn(0, 4, 5));
        assertEquals(1, ChannelHome.buttonIndexForColumn(1, 4, 5));
        assertEquals(2, ChannelHome.buttonIndexForColumn(2, 4, 5));
        assertEquals(4, ChannelHome.buttonIndexForColumn(3, 4, 5));
    }

    @Test public void fourColumnsLandOnEachBarButtonBeneathThem() {
        assertEquals(0, ChannelHome.buttonIndexForColumn(0, 4, 4));
        assertEquals(1, ChannelHome.buttonIndexForColumn(1, 4, 4));
        assertEquals(2, ChannelHome.buttonIndexForColumn(2, 4, 4));
        assertEquals(3, ChannelHome.buttonIndexForColumn(3, 4, 4));
    }

    @Test public void withoutStoreButtonLeftHalfLandsOnProfileAndRightHalfOnNetworkAndSettings() {
        assertEquals(0, ChannelHome.buttonIndexForColumn(0, 4, 3));
        assertEquals(0, ChannelHome.buttonIndexForColumn(1, 4, 3));
        assertEquals(1, ChannelHome.buttonIndexForColumn(2, 4, 3));
        assertEquals(2, ChannelHome.buttonIndexForColumn(3, 4, 3));
    }

    @Test public void spanishDateAndBrokenPatternFallBackCleanly() {
        Locale es = Locale.forLanguageTag("es-ES");
        String formatted = LocalDate.of(2026, 11, 17).format(ChannelHome.dateFormat("not[a[pattern", es));
        assertTrue(formatted, formatted.toLowerCase(es).contains("noviembre"));
        assertTrue(formatted, formatted.contains("2026"));
    }
}
