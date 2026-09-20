package de.shakie.iss

import de.shakie.iss.orbit.*
import org.junit.Test
import kotlin.math.*

class VisibilityPredictionTest {
    private fun near(a: Long,b: Long) {check(abs(a-b)<=1000L){"$a != $b"}}
    private val elevation: (Long)->Double = { t -> 30.0-abs(t-120000L)/2000.0 }
    @Test fun allAndNoIllumination() {
        check(VisibilityWindows.find(0,60000){true} == listOf(PassWindow(0,60000)))
        check(VisibilityWindows.find(0,60000){false}.isEmpty())
        check(VisibilityWindows.find(100,100){true}.isEmpty())
    }
    @Test fun transitionEdgesAreRefined() {
        val spans=VisibilityWindows.find(0,60000){it>=12345 && it<37654}
        check(spans.size==1);near(spans[0].startMillis,12345);near(spans[0].endMillis,37654)
    }
    @Test fun disconnectedWindowsAreNotJoined() {
        val spans=VisibilityWindows.find(0,100000){it in 10000L..30000L || it in 60000L..80000L}
        check(spans.size==2)
        near(spans[0].startMillis,10000);near(spans[0].endMillis,30000)
        near(spans[1].startMillis,60000);near(spans[1].endMillis,80000)
    }
    @Test fun dayPassIsNotMarkedOpticallyVisible() {
        val p=PassPredictor.findNextPassFromSamples(0,elevation,{true},{false})!!
        check(!p.isVisibleOptically&&p.visibleWindows.isEmpty()&&p.sunlitWindows.isNotEmpty())
        check(p.visibilityDescription(0).contains("Himmel"))
        check(p.sunlightDescription(0).contains("beleuchtet"))
    }
    @Test fun eclipsedPassIsNotMarkedVisible() {
        val p=PassPredictor.findNextPassFromSamples(0,elevation,{false},{true})!!
        check(!p.isVisibleOptically&&p.sunlitWindows.isEmpty())
        check(p.visibilityDescription(0).contains("Erdschatten"))
    }
    @Test fun sunlightAndDarknessAreIntersectedWithThePass() {
        val p=PassPredictor.findNextPassFromSamples(0,elevation,{it>=90000},{it<150000})!!
        check(p.isVisibleOptically)
        near(p.riseTimeMillis,60000);near(p.setTimeMillis,180000)
        near(p.visibleWindows.single().startMillis,90000);near(p.visibleWindows.single().endMillis,150000)
        check(p.sunlitWindows.single().endMillis>=p.visibleWindows.single().endMillis)
        check(p.visibleWindows.all{it.startMillis>=p.riseTimeMillis&&it.endMillis<=p.setTimeMillis})
    }
    @Test fun currentPassIncludesItsEarlierRiseAndPeak() {
        val p=PassPredictor.findNextPassFromSamples(155000,elevation,{true},{true})!!
        near(p.riseTimeMillis,60000);near(p.maxTimeMillis,120000)
        near(p.setTimeMillis,180000)
        check(p.formatDescription(155000).startsWith("Jetzt"))
    }
    @Test fun insignificantPassDoesNotHideTheNextSignificantOne() {
        val p=PassPredictor.findNextPassFromSamples(0,{t-> max(8-abs(t-120000L)/2000.0,25-abs(t-500000L)/2000.0)},{true},{true})!!
        near(p.maxTimeMillis,500000)
        check(p.maxElevationDeg>=24.5)
    }
    @Test fun peakRefinementDoesNotMissThresholdBetweenScanSamples() {
        val p=PassPredictor.findNextPassFromSamples(0,{t->12.2-abs(t-105000L)/2000.0},{true},{true})!!
        near(p.maxTimeMillis,105000);check(p.maxElevationDeg>=12)
    }
    @Test fun noPassRemainsUnknownRatherThanVisible() {
        check(PassPredictor.findNextPassFromSamples(0,{-10.0},{true},{true})==null)
        val legacy=IssPass(100,200,300,40.0,true)
        check(legacy.visibilityDescription(0).contains("berechnet"))
    }
    @Test fun solarAltitudeMatchesSimpleIndependentCases() {
        check(abs(VisibilityWindows.solarElevation(0.0,0.0,0.0,0.0)-90.0)<1e-8)
        check(abs(VisibilityWindows.solarElevation(0.0,180.0,0.0,0.0)+90.0)<1e-8)
        check(abs(VisibilityWindows.solarElevation(0.0,90.0,0.0,0.0))<1e-8)
        check(abs(VisibilityWindows.solarElevation(90.0,123.0,23.44,0.0)-23.44)<1e-8)
    }
    @Test fun midnightAndCountdownUseTheRiseNotPeak() {
        val old=java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val start=java.time.Instant.parse("2026-09-20T23:59:00Z").toEpochMilli()
            val p=IssPass(start,start+120000,start+240000,40.0,true,listOf(PassWindow(start,start+240000)),listOf(PassWindow(start,start+240000)),true)
            check(p.formatDescription(start-60000).contains("In 1 Min. ab 23:59:00"))
            check(p.visibilityDescription(start-60000).contains("21.09. 00:03:00"))
            check(p.formatDescription(start+240001).contains("beendet"))
        } finally {java.util.TimeZone.setDefault(old)}
    }
}
