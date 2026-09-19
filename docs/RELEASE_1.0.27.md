# Release 1.0.27: geprüfte Zoom-Korrektur

Version im Build: **1.0.27 / versionCode 28**. Die Kamerakorrektur liegt bereits im Vorgängercommit `b1821b3ee639dd9ce3435c0927bcaf2ad10008fb`. Dieser Release-Vorbereitungscommit verändert die Kameralogik nicht nochmals; er kennzeichnet den erneut geprüften Stand eindeutig und erhöht die Version gegenüber der veröffentlichten 1.0.26.

**Kein ZIP und keinen alten Patch mehr einspielen.** Das zuvor bereitgestellte `ISS-Spotter-Zoom-Fix.zip` enthält eine ältere Variante mit anderer Kollisionsbehandlung. Ausschließlich den aktuellen Git-Stand bauen. Diese Datei ist für den nächsten Release maßgeblich; ältere Übergaben bleiben als Entwicklungsprotokoll erhalten.

## Erwartetes Verhalten

Die ISS bleibt auf jeder Zoomstufe der Drehpunkt. Pinch verändert die tatsächliche Entfernung, weder das Blickziel noch die Blickrichtung. Das automatische Wandern des Ankers ins Erdinnere ist entfernt. Die Kamerabewegung ist auf die raumseitige Halbkugel um die ISS begrenzt (Seitenansicht bis 85 Grad über der örtlichen Tangentialebene); dadurch kein Durchfahren der Erde. Die Erde passt beim maximalen Abstand vollständig ins Bild, wird aber nicht künstlich in die Mitte geschoben. Physischer ISS-Maßstab, Atmosphäre, Sternkoordinaten, Flugbahnen, Kartenmodi und AR bleiben unverändert.

Der lokale Culling-Fernbereich und die modellabhängige Nahgrenze verhindern die numerisch reproduzierte degenerierte Begrenzungsebene der alten Nahzoom-Konfiguration. Die tatsächliche GPU-/Geräteausgabe ist noch zu prüfen; die numerische Prüfung ist kein Ersatz dafür.

## Erneute Prüfung am 19.09.2026

Die produktiven Dateien wurden aus dem GitHub-Stand übernommen und vor der Ausführung über ihre Git-Blob-Hashes abgeglichen:

- `OrbitCameraController.kt`: `2a37702bcb6801240f5e3bfbfcf717c4f38985a8`
- `OrbitGeometry.kt`: `9d35c47a65233c2dd06b2e25c2df376f3ccc812d`

Mit dem lokalen Kotlin/JVM-Compiler und einem separaten Prüflauf gegen diese unveränderten Klassen bestanden:

- 336.960 tatsächliche Kameraposen: vollständiger Hin- und Rückzoom, monotone ISS-/Erdabstände, unveränderter ISS-Drehpunkt und Blickrichtung, endliche Nordausrichtung.
- 17.280 numerische Float-Frusta: endliche Ebenen, ISS vor der Nahgrenze, Erde innerhalb des Fernbereichs, Erdkugel-Bounding-Box in der Default-Blickrichtung nicht ausgeblendet.
- 720 Off-Axis-Erdsilhouetten bei maximalem Zoom: vollständig innerhalb des Bildes und höchstens 48 Prozent der jeweiligen Viewport-Abmessung.
- 36 Pinch-/Umkehrpaare: gleichmäßiges Verhältnis der tatsächlichen Entfernung auf unterschiedlichen Zoomstufen.
- Negativkontrolle reproduziert die alte degenerierte Far-Ebene bei `far=5000`; Reset, ungültige Pinch-Eingaben und unveränderter 109-m-Maßstab geprüft.

Die Frustum-Prüfung ist eine mathematische Referenzrechnung, keine Ausführung von Filament. Keine Android-Testdoubles wurden für die beiden produktiven Geometrieklassen benötigt. **Nicht ausgeführt:** kompletter Gradle-Testlauf, Android-Build, GLB-Dateiprüfung, GPU-Rendering und Smartphone-Test. Die im Repository vorhandenen Regressionstests bleiben unverändert.

## Auftrag an Gemini: nur Build, Abnahme und Veröffentlichung

1. Lokale Änderungen erhalten, `main` mit `git pull --ff-only` aktualisieren. Prüfen, dass der gebaute Commit die Kamerakorrektur `b1821b3` und diese Release-Vorbereitung enthält. Keine Kameraänderungen oder Dateien aus dem ZIP übernehmen.
2. Version 1.0.27 / Code 28 ist schon gesetzt; nicht erneut erhöhen. Vor dem Taggen prüfen, dass `v1.0.27` nicht inzwischen anderweitig vergeben wurde. Bestehende Tags/Releases nicht überschreiben.
3. `gradlew.bat testDebugUnitTest assembleDebug` ausführen. Insbesondere `OrbitZoomRegressionTest`, `OrbitSceneRegressionTest` und den GLB-Hash-Test im vollständigen Checkout ausführen. Bestehende Android-Instrumentierungstests auf verfügbarem Gerät/Emulator ausführen. Keine Tests abschwächen.
4. Bei eingefrorener Zeit im Hoch- und Querformat vom minimalen bis zum maximalen Zoom fahren und zurück. Kein plötzliches Näherkommen, Verschwinden oder Sprung zur Erdoberfläche. In Nah-, Mittel- und Fernansicht drehen: ISS bleibt Drehpunkt. Seitenansicht, Reset, Pinch-Umkehr und vollständige Erde in großer Entfernung prüfen. Atmosphäre, Sterne, Flugbahnen und Wechsel der Kartenmodi kurz mitprüfen. Testumgebung konkret nennen.
5. Bei Fehlern nicht veröffentlichen: konkrete Meldung beziehungsweise reproduzierbare Szene an ChatGPT zurückgeben. Keine neue Kameralösung entwerfen. Keine unnötige Shader-Neukompilierung.
6. Nach bestandener Abnahme mit dem bisherigen Release-Schlüssel `gradlew.bat assembleRelease` ausführen. Signatur, Version und Updateinstallation prüfen; SHA-256 berechnen. Keine Schlüssel, Passwörter oder Tokens hochladen.
7. Exakt den gebauten und geprüften Commit als `v1.0.27` taggen und das Release mit signierter APK und Prüfsumme veröffentlichen. Release-Link, vollständigen Commit und tatsächlich ausgeführte Tests zurückgeben. Kein allgemeines „visuell geprüft“ ohne eine wirkliche Prüfung ausgeben.

Release Notes: „Zoom behält die ISS als Drehpunkt; ungewollte Kamerasprünge beim Herauszoomen und numerisches Ausblenden im Nahbereich korrigiert; gleichmäßige Pinch-Reaktion. Maßstab und vorhandene Darstellungsmodi unverändert.“
