# Release 1.0.30: kompakte Infos, Sichtfenster und AR-Aufnahmen

Basis: `e073bd46b74ffddd43cd5d79374e7d1737452d2c` (1.0.29 / Code 30).
Version ist auf **1.0.30 / Code 31** vorbereitet. Dieses Dokument ersetzt für dieses Release frühere Kamera-/Zoom-Aufträge. Die funktionierende Filament-Orbitsteuerung, ihre Nordreferenz, der ISS-Maßstab, die Erdmaterialien und die Flugbahndarstellung werden nicht verändert.

## Informationsbox

Beim Start sind Koordinaten sowie Geschwindigkeits-/Entfernungsdetails eingeklappt. Überflug, überflogenes Gebiet und aktueller Beleuchtungsstatus bleiben sichtbar. „Details“ zeigt die weiteren Angaben. Hinzu kommen vorausberechnete Sichtfenster oder eine ausdrückliche Meldung für Erdschatten beziehungsweise zu hellen Himmel.

Der frühere Überflugrechner setzte `isVisibleOptically` immer auf true. Jetzt werden drei Bedingungen getrennt berechnet: ISS über dem geometrischen Horizont, beleuchtete ISS und ausreichend dunkler Himmel am Standort. In den Details stehen die reinen Sonnenlichtfenster separat. Schwellen: wie bisher ausgewählte Überflüge ab 12 Grad maximaler Höhe; Sonnenlichtfaktor mindestens 0,15; Sonnenhöhe am Standort höchstens -6 Grad als konservative Dämmerungsregel. Das ist keine Wetter-, Hindernis- oder Helligkeitsgarantie. Sekunden in der Anzeige kennzeichnen die berechneten Grenzen, nicht die Genauigkeit der Bahnelemente.

Horizontübertritte werden verfeinert; Beleuchtungsintervalle werden alle fünf Sekunden abgetastet und erkannte Grenzen bis auf höchstens eine Sekunde eingegrenzt. Sehr kurze Intervalle unterhalb der Abtastweite können fehlen. Ein bereits laufender Überflug wird mit seinem tatsächlichen früheren Beginn berücksichtigt. Aufgang, Maximum und Untergang werden nicht mehr in einer widersprüchlichen Countdown-Zeile vermischt. Datumswechsel sind in lokalen Zeitangaben erkennbar.

Der Tracker berechnet die Vorhersage im Hintergrund mit Cache und verwirft Ergebnisse eines alten Standorts oder alter Bahnelemente. Fehler bekommen eine Wiederholpause, statt in jedem Bild eine neue Berechnung zu starten. Ohne Standort wird keine Berliner Sichtbarkeitsvorhersage vorgetäuscht. Die ortsunabhängige Bodenspur kann trotzdem berechnet werden; ihre Hilfsreferenz wird nicht als echter Beobachter verwendet. Standortänderungen aus der Activity werden atomar übernommen. Auch eine nur ungefähre Standortfreigabe kann über den Netzwerkprovider eine Position liefern.

Der vorhandene vereinfachte `Sgp4Propagator` bleibt unverändert. Die Genauigkeit dieser Bahnnäherung und das Alter der TLE bleiben Grenzen der Prognose.

## Kamera und Aufnahmen

Neue Bedienelemente ausschließlich in der Betrachteransicht:

- Foto, Video/Stop und gespeicherte Option „Overlay: AN/AUS“.
- Pinch-Zoom, Tippen für Autofokus/Belichtungsmessung, visuelle Fokusrückmeldung.
- 1×-Reset, Doppeltipp zum Zoom-Reset, Öffnen der letzten Aufnahme.
- Aufnahmedauer/Status und Bildschirm aktiv halten während eines Videos.

Fotos und Videos verwenden CameraX ImageCapture beziehungsweise Recorder, keine Bildschirmaufnahme. Das Aufnahme-Overlay enthält die AR-Zeichnung (ISS, Flugbahn, Stern-/Horizontinformationen), nicht Bedienknöpfe, große Telemetriebox, Android-Statusleiste oder Navigationsleiste. Die Option ist während einer Aufnahme gesperrt. Videos werden bewusst **ohne Ton** aufgenommen; es wird keine Mikrofonfreigabe angefordert.

Gemeinsamer CameraX ViewPort für Vorschau und Aufnahmen. Für das Einzeichnen wird Preview -> Sensor -> Foto-/Videopuffer transformiert; anschließend gelten die jeweiligen Crop-/Rotationsinformationen. Für Video wird OverlayEffect nur auf VIDEO_CAPTURE angewendet, nicht zusätzlich in die sichtbare Vorschau eingebrannt. Fotos werden mit optionalem Overlay als korrekt gedrehtes JPEG gespeichert. Das Bitmap-Verfahren erhält nicht sämtliche EXIF-Kameradaten. Bilder und Videos landen über MediaStore in der Galerie; ab Android 10 in Pictures/ISS-Spotter beziehungsweise Movies/ISS-Spotter. Android 9 benötigt dafür eine erst beim Auslösen angeforderte Speicherfreigabe, im Manifest auf API 28 begrenzt.

Beim Verlassen der Kamera wird Video zunächst beendet/finalisiert. Bereits erzeugte Aufnahmen bei dokumentierten vorzeitigen Beendigungen (z.B. Kamera durch Lifecycle gestoppt) werden mit Hinweis erhalten; echte Encoder-/Ausgabefehler werden nicht als Erfolg ausgegeben. Keine heimliche Hintergrundaufnahme. Wenn eine Gerätekombination keine Video-Effekte unterstützt, ist das kenntlich und Aufnahme ohne Overlay erfordert das bewusste Ausschalten der Option. Wenn Video insgesamt nicht gebunden werden kann, bleibt Foto verfügbar und Video ist deaktiviert.

## Zoom, Objektive und Kalibrierung

Die logische Android-Mehrfachkamera wird bevorzugt, damit der Hersteller innerhalb ihres Zoombereichs physische Objektive wechseln kann. Wo Android zusätzliche Rückkameras samt relativem Bildwinkel freigibt, können sie außerhalb einer laufenden Aufnahme mit Hysterese automatisch gewählt werden. Keine geratenen Samsung-Modellnummern oder versteckten Kamerakennungen.

**Einschränkung während Video:** Automatischer physischer Wechsel innerhalb einer logischen Kamera ist dem Gerät überlassen. Eine separat gebundene Kamera wird nicht während eines Videos durch eine andere ersetzt; das würde die Aufnahme unterbrechen. In diesem Fall bleibt der Zoom im verfügbaren Bereich der laufenden Kamera, mit Hinweis bei Erreichen der Grenze.

Die Projektion erhält laufende CaptureResult-Metadaten: aktive physische ID (soweit vorhanden), Brennweite, Pixelraster, Crop/Zoom und Verzeichnung. Physischer Sensor-Crop wird bevorzugt, wenn verfügbar; sonst wird eine ausdrücklich als Näherung bezeichnete Rekonstruktion des verbleibenden Digitalzooms verwendet. Alte Objektivbrennweiten werden nicht unbemerkt auf neue Sensorgrößen angewendet. Unbekannte relative Objektivfaktoren werden nicht als verifizierte Wechselziele behandelt.

Verzeichnung wird nicht zweimal angewendet: Bei entzerrter Vorschau wird keine zusätzliche Brown-Conrady-Verformung aufgebracht. Bei unentzerrter Vorschau werden passende vorhandene LENS_DISTORTION-Koeffizienten in den Koordinaten der intrinsischen Kalibrierung verwendet. Der künstliche Horizont benutzt dieselbe Projektion. Digitaler Zoom und aspectbedingter Zuschnitt werden getrennt behandelt. Beim Neuverbinden wird eine veraltete AR-Projektion nicht weiter angezeigt.

**Keine Behauptung vollständiger Gerätekalibrierung:** Herstellerinterne Warps, OIS, optische Achsabweichungen, fehlende physische Metadaten, Sensor-/Vorschauverzögerung und Magnetometerfehler bleiben geräteabhängig. Es wird die jüngste verfügbare AR-Orientierung verwendet, keine zeitlich rückwirkende Rekonstruktion für jede Belichtungszeile. Insbesondere schnelle Schwenks sind im Hardwaretest zu prüfen. Die Behandlung nicht deckungsgleicher Pre-Correction-/Active-Raster und fehlender physischer Crops ist als Näherung ausgewiesen, nicht als exakt kalibriert.

## Tatsächlich ausgeführte lokale Prüfungen

- 22 neue Kotlin/JVM-Testmethoden aus VisibilityPredictionTest und LensProjectionMathTest bestanden: Tag/Nacht/Erdschatten, getrennte Intervalle, laufender Überflug, Peak-/Horizontgrenzen, Datumswechsel; Zoom/Crop, Teleobjektiv plus Restzoom, Verzeichnung, Pixelraster, Objektivwahl.
- Die fünf neuen CaptureTransformTest-Methoden liefen lokal mit einer affinen Matrix-Testimplementierung. Sie prüfen Reihenfolge/Geometrie der Produktionsabbildungen. **Das ist kein echter Android-Matrix- oder Aufnahmetest.** Im Repository liegen sie deshalb unter androidTest und müssen dort nochmals mit Android ausgeführt werden.
- Neue Kamera-/Aufnahme-/UI-Klassen wurden zusätzlich mit lokal erstellten API-Fassaden vom Kotlin-Compiler geprüft; Tracker mit vorhandener lokaler Coroutines-Bibliothek und abhängigen Typfassaden. Diese Prüfung findet Kotlin-Typ-/Syntaxprobleme, validiert aber weder die vollständigen Android/CameraX-Artefakte noch deren Laufzeitverhalten.
- Die ursprüngliche MainActivity und das ursprüngliche AR-Overlay wurden vor den gezielten Eingriffen über ihre Git-Blob-Hashes abgeglichen (`6bebc3b...`, `fb07a22...`). Manifest wurde als XML geprüft. Keine Änderungen an OrbitCameraController, IssFilamentView, Erdgeometrie, Shaderquellen oder .filamat/.glb.

**Nicht ausgeführt:** voller Gradle-/Android-Build, vollständige bestehende Testsuite, echte Kamera-/Video-/GPU-Aufnahme, Samsung-Objektivwechsel, Fotos/Galerie/MP4-Wiedergabe und Gerätesignierung. Das ist vor dem Release erforderlich. Kein erfolgreiches Hardwareergebnis behaupten.

## Gemini: Abnahme, Build und Release

1. Lokale Änderungen erhalten, main per Fast-forward aktualisieren. Nur den aktuellen Stand bauen; keine alten Kamera-Patches einspielen. Version 1.0.30 / Code 31 ist gesetzt; nicht nochmals erhöhen. Vorhandenen Tag/Release nicht überschreiben.
2. `gradlew.bat testDebugUnitTest assembleDebug` ausführen. Alle vorhandenen Orbit-/Nord-/Zoomtests bleiben aktiv. Neue VisibilityPredictionTest und LensProjectionMathTest sowie GLB-Prüfung mitprüfen. Fehler nicht durch Entfernen von Tests kaschieren.
3. `connectedDebugAndroidTest` mit bestehendem CameraProjectionRegressionTest und neuem CaptureTransformTest ausführen. Auf einem echten Gerät zusätzlich Fokus, Pinch und alle zugänglichen Objektive prüfen. Ein Emulator bestätigt keine Samsung-Hardwarekalibrierung.
4. Aufnahmeprüfung mit und ohne Overlay: Foto sowie Video in Galerie öffnen; korrekte Orientierung und gleicher Ausschnitt, keine Bedienknöpfe/Systemleisten im Medium. Bekannten entfernten Punkt links/rechts und oben/unten nahe am Bildrand prüfen, dann Zoom und Objektivwechsel. Overlay darf beim Wechsel nicht mit dem alten Objektivmodell weiterlaufen. Schnell schwenken und mögliche Latenz dokumentieren. FHD/HD-Rückfall und fehlende Effekte nicht als voll unterstützt darstellen.
5. Recording starten/stoppen, sofortigen Stop, längeren Clip, App-Pause, Kamera/ISS-Moduswechsel, abgelehnte und erneut gewährte Kamera-/Speicherfreigabe prüfen. Laufende Videos werden nicht durch manuelles Objektivrebind unterbrochen. MP4 muss abspielbar sein; Status muss zum tatsächlichen Ergebnis passen. Videos bleiben ohne Ton.
6. HUD nach Neustart kompakt; Details ein/aus. Echter Standort nötig, kein Berliner Defaultpass. Beleuchtungsfenster und Dunkelheitsregel getrennt kontrollieren; mindestens einen Tageslicht-, Schatten- und sichtbaren Pass prüfen. Fehlerzustand/kein passendes Ereignis verständlich anzeigen.
7. Nur nach erfolgreichem Build und Abnahme mit bisherigem Release-Schlüssel `assembleRelease`, Signatur/Updateinstallation/Version prüfen, SHA-256 erstellen. Genau den gebauten Commit taggen und Release mit signierter APK und Prüfsumme veröffentlichen. Keine Schlüssel/Passwörter veröffentlichen. Bei konkreten Build-/Funktionsfehlern an ChatGPT zurückmelden, keine neue eigenständige Kamerasteuerung entwerfen. Tatsächliche Testumgebung und offene Hardwaregrenzen nennen.

## Primärdokumentation

- https://developer.android.com/reference/androidx/camera/effects/OverlayEffect
- https://developer.android.com/reference/androidx/camera/effects/Frame
- https://developer.android.com/reference/androidx/camera/core/ImageInfo
- https://developer.android.com/reference/androidx/camera/core/ImageProxy
- https://developer.android.com/media/camera/camerax/video-capture
- https://developer.android.com/reference/androidx/camera/video/VideoRecordEvent.Finalize
- https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
- https://developer.android.com/reference/android/hardware/camera2/CaptureResult
