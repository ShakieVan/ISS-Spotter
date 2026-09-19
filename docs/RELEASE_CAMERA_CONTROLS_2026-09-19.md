# Release-Übergabe: Kameradrehung nach 1.0.27

Basis: `d3d4fa64bb99f3f6a31773d4cd1f8e6932ee5c00` (1.0.27 / Code 28).
Diese Übergabe ersetzt die Aussagen zur Kameradrehung und Erd-Kollisionssperre aus `RELEASE_1.0.27.md`. Der funktionierende physische Zoom und der ISS-Maßstab bleiben erhalten.

## Reproduzierte Ursache

Der vorherige Controller kombinierte die lokale Orbitbewegung um die ISS mit einer separat erzwungenen Ausrichtung auf die globale Y-Achse. Diese globale Nordrichtung ist nicht identisch mit der örtlichen Nordtangente auf der Erdoberfläche. In der Default-Perspektive kann die Projektion bei ungefähr 32 Grad südlicher Breite zusammenfallen; südlich davon wird die Kamera gegenüber der gewünschten lokalen Ausgangsausrichtung um 180 Grad gerollt. Die alten Prüfungen `up.y >= 0` akzeptierten diesen Fehler.

Zusätzlich liefen horizontale Wischbewegungen um die örtliche Radialachse, nicht zuverlässig um die Bildschirm-Hochachse. Pitch war auf -53 bis +32 Grad begrenzt. Das führte zu unerwarteten Kreisbewegungen und einem harten Anschlag.

## Umsetzung

- Reset verwendet wieder den geografisch lokalen Rahmen: Osten, Nordtangente und radial nach außen. Die Erde liegt in der Ausgangsperspektive unterhalb der Station, örtliches Norden oben, Osten rechts – auf beiden Halbkugeln.
- Wischgesten drehen um die jeweils aktuellen Bildschirmachsen. Vorwärts- und Hochvektor werden gemeinsam als orthonormale Basis fortgeführt. Keine unabhängige globale-Y-Nivellierung und kein spontaner Rollwechsel beim Überqueren einer Polrichtung.
- Aufwärts wischen führt aus der Ausgangsansicht zum senkrechteren Blick auf die Erde. Die Bewegung kann darüber hinaus fortgesetzt werden. Keine neue Pole-/Pitch- oder Erdkollisionsbegrenzung.
- Ein vollständiger freier Überschlag wird kontinuierlich ausgeführt. Dabei wird nicht behauptet, dass das geografische oder himmlische Norden unabhängig von beliebigen Überschlägen immer an derselben Bildschirmkante bleibt. Eine solche erzwungene Neuausrichtung würde die unerwünschten Sprünge wieder einführen. Reset stellt die lokale Nordausrichtung wieder her.
- Die ISS bleibt der feste Drehpunkt. Der physische Maßstab, Standardabstand und Pinch-Gain sowie die funktionierenden Nah-/Fern-Culling-Funktionen bleiben erhalten. Die maximale Entfernung erhält lediglich einen zusätzlichen LEO-Pivot-Sicherheitsabstand, damit die ganze Erde auch aus den jetzt zugelassenen erdseitigen Perspektiven höchstens ungefähr halbe Bildschirmbreite einnimmt.
- Der Nutzer hat das Durchqueren der Erde ausdrücklich erlaubt. Innerhalb der Erdkugel werden Erdmesh und Atmosphäre ausgeblendet, ohne die Kamera zu verschieben; außerhalb wieder eingeblendet. Die Referenzmodus-Präferenz für die Atmosphäre bleibt wirksam. Die Sternbeschriftungen beachten dieselbe Innenraum-Regel. Erdkoordinaten, Sternpositionen, Wolken, Flugbahnen und AR-Projektion wurden nicht verändert.
- Nach dem Loslassen eines Fingers beim Zoomen wird der verbleibende Finger neu als Bezug gesetzt. Ein Fingerindex-Wechsel wird nicht mehr als plötzliches Kameradrehen ausgewertet.
- Absolute `yawOffsetDeg`/`pitchOffsetDeg` bleiben für ADB-Szenen erhalten. Touch verwendet stattdessen `orbitByPixels`; beliebige freie Orientierungen werden nicht in zwei Eulerwinkel zurückgezwungen.

Keine Shaderquellen oder kompilierten Filament-Materialien verändert. Keine Shader-Neukompilierung erforderlich.

## Tatsächlich ausgeführte Tests

Die sechs hochgeladenen Kotlin-Dateien wurden über ihre Git-Blob-Hashes mit den lokalen Prüfdateien abgeglichen. Ein lokaler Kotlin/JVM-Lauf mit kleiner Testannotation und Reflection-Runner bestand mit **34 Testmethoden, 0 Fehlern**:

- 9 Zoom-Regressionsmethoden, darunter 280.800 tatsächliche Kameraposen auf einem geraden Zoomweg mit festem ISS-Drehpunkt;
- 11 neue Rotationsprüfungen: lokale Nordausrichtung/Erde unten auf beiden Halbkugeln, negative Gegenprobe des alten Fehlers, Übergang über -32 Grad Breite, Swipe bis zum Nadir und darüber hinaus, Bildschirmachsen nach vorigen Drehungen, wiederholte 720-Grad-Bewegungen, Umkehrbarkeit/Unterteilung diagonaler Gesten, unveränderte Orientierung beim Zoomen, erlaubte Erd-Durchquerung und Reset;
- 14 bestehende Szenen-/Stern-/Flugbahntests, ergänzt um die jetzt gewünschte freie Bewegung statt der ausdrücklich verworfenen Kollisionssperre.

Die Tests prüfen Produktions-Geometriecode. Die Frustum-Berechnung ist eine mathematische Referenz, keine Ausführung von Filament. Die Source-Checks der Renderer-Anbindung ersetzen keinen Android-Gestentest.

**Nicht ausgeführt:** vollständiger Gradle-/Android-Build, GPU-Darstellung und Smartphone-Test. Der unveränderte GLB-Hash-Test wurde lokal übersprungen, weil die Modelldatei nicht im lokalen Standalone-Testverzeichnis lag; er muss im vollständigen Checkout laufen. Alte pauschale Anforderungen „Kamera immer außerhalb der Erde“/„Erdabstand bei jeder beliebigen Blickrichtung monoton“ wurden absichtlich durch den jetzt gewünschten geraden Kamerapfad ohne Versetzen ersetzt.

## Auftrag an Gemini: nur Build, Gerätetest und Veröffentlichung

1. Lokale Änderungen erhalten und `main` per `git pull --ff-only` aktualisieren. Keine Dateien aus alten ZIP-Patches oder früheren Kameravarianten übernehmen.
2. Im Repository steht noch 1.0.27 / Code 28. Wenn frei, auf 1.0.28 / Code 29 erhöhen und `archivesName` angleichen. Vorhandene Tags/Releases nicht überschreiben.
3. `gradlew.bat testDebugUnitTest assembleDebug` ausführen; insbesondere OrbitRotationRegressionTest, OrbitZoomRegressionTest und OrbitSceneRegressionTest sowie den GLB-Hash-Test. Die vorhandenen Android-Instrumentierungstests ebenfalls ausführen, soweit ein Gerät/Emulator vorhanden ist. Keine Tests abschwächen.
4. Mit eingefrorener Szene prüfen: Reset bei etwa -50, -32 und +50 Grad Breite; Erde unterhalb der ISS und lokale Nordorientierung. Nur nach oben wischen bis zum senkrechten Blick und darüber hinaus, danach nur links/rechts wischen. Lange reine horizontale und vertikale Bewegungen dürfen keine automatische Rollkorrektur oder Anschläge auslösen. Diagonal wischen und zurück. Reset muss reproduzierbar sein.
5. Nah-/Mittel-/Fernzoom prüfen: ISS bleibt Pivot, kein durch den Zoom ausgelöster Orientierungswechsel. Mit großer Entfernung die Kamera absichtlich durch die Erde drehen; innen Sternenhimmel, außen Erde/Atmosphäre wieder vorhanden. Referenzmodus bei Innen-/Außenwechsel berücksichtigen. Nach Pinch einen Finger liegen lassen und weiterwischen: keine Sprünge. Hoch-/Querformat sowie vorhandene Flugbahnen und Kartenmodi kurz mitprüfen.
6. Nur nach erfolgreicher Prüfung mit dem bisherigen Schlüssel Release-APK bauen, Signatur/Version/Updateinstallation prüfen und SHA-256 berechnen. Versionsänderung pushen, genau den gebauten Commit taggen und Release mit APK/Prüfsumme veröffentlichen. Keine Schlüssel oder Passwörter hochladen.
7. Bei einem konkreten Build-/Gerätefehler die Fehlermeldung bzw. reproduzierbare Szene an ChatGPT zurückgeben, keine eigene neue Kamerasteuerung implementieren. Testumgebung und nicht ausgeführte Prüfungen ausdrücklich nennen.

Release Notes: „Lokale Nordausrichtung nach Reset und Wischsteuerung der ISS-Kamera korrigiert; freie Drehung ohne automatische 180-Grad-Sprünge; Durchquerung der Erde mit transparenter Innenansicht erlaubt; Zoom und physischer ISS-Maßstab erhalten.“
