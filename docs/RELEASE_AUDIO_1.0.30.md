# Ergänzung zu Release 1.0.30: Tonaufnahme und Mikrofonpegel

Basis: `ced11fd5809cfb28e893e05eac57827db61ac186`. Version bleibt **1.0.30 / Code 31**, da der Tag bei der Prüfung noch nicht existierte. Dieser Auftrag **ersetzt alle Aussagen „ohne Ton“** in `RELEASE_CAPTURE_VISIBILITY_2026-09-20.md`. Dessen übrige Aufnahme-, Objektiv- und Sichtfenster-Prüfungen bleiben erforderlich.

## Verhalten

- Videos aktivieren jetzt CameraX `PendingRecording.withAudioEnabled()`. Mikrofonfreigabe zur Laufzeit, ausdrücklich erst nach Antippen von Video; weder Vorschau noch Fotos öffnen das Mikrofon.
- Nach der erstmaligen Freigabe nochmals Video antippen. Ein Berechtigungsdialog kann die Kamera pausieren/neu binden; deshalb kein gespeicherter Autostart, der später unerwartet eine Aufnahme beginnt. Nach Ablehnung kein stiller Rückfall auf ein Video ohne Ton. Bei dauerhafter Ablehnung führt ein Dialog zu den App-Berechtigungen. Fotos bleiben möglich.
- Pegelzeile mit grün/gelb/rotem Balken und relativem Spitzenpegel in dBFS. Grundlage ist `RecordingStats.audioStats.audioAmplitude`, verfügbar seit CameraX 1.4.0 und damit in der vorhandenen 1.4.1. Maximalwert über die Audiokanäle, keine getrennte Stereoanzeige und kein kalibriertes Schallpegelmessgerät (dB SPL). Boden der Balkenskala -60 dBFS, Warnbereich nahe Vollaussteuerung.
- Keine zweite AudioRecord-/MediaRecorder-Instanz. Die Pegelwerte stammen aus derselben Recorder-Instanz, die die Tonspur schreibt. Anzeige nur während Videoaufnahme; kein zusätzliches Dauerlauschen für einen Vorschaupegel.
- Android-Systemstummschaltung, deaktiviertes Audio, Mikrofon-/Encoderfehler werden getrennt von echter Stille dargestellt. Ein ungültiger oder über zwei Sekunden alter Pegel wird nicht als aktueller Messwert ausgegeben. Späte Statusereignisse setzen die Anzeige während Finalisierung nicht wieder in Betrieb.
- Eine Tonunterbrechung wird während der Aufnahme angezeigt und nach dem Speichern vermerkt. Bereits brauchbares Video wird nicht nur wegen eines Tonproblems gelöscht. Kein pauschales „mit Ton erfolgreich“, wenn der Recorder keinen aktiven Ton bestätigt hat. Finalize-Ergebnis und Videofehlerbehandlung bleiben erhalten.
- Die Pegelanzeige ist ein eigenes `MicrophoneLevelView` im Bedienpanel. Sie gehört **nicht** zum `IssCalloutOverlayView`. Foto-/Video-Compositing zeichnet unverändert nur `overlay.draw(canvas)`, nie Container oder Bedienpanel. Damit bleibt der Pegel auch bei „Overlay: AN“ aus dem gespeicherten Bild; dieselbe Trennung gilt für Schaltflächen und Statusanzeige.
- Orbitsteuerung, Nord-Lock, Maßstab, Zoom, Flugbahnen, Linsenkorrektur, Fotoverarbeitung, Shader und Bibliotheksversionen bleiben unverändert.

## Prüfung in ChatGPT

- Ausgangsdateien ArCaptureSession, ObserverCameraControls und Manifest anhand ihrer Git-Blob-Hashes abgeglichen.
- **16 lokale Kotlin/JVM-Testmethoden bestanden**: logarithmische Pegelskala, 10001 Amplitudenwerte, Stille vs. fehlende Werte, NaN/Unendlich, Systemstummschaltung, Wiederherstellung, Finalisierung, verspätete Ereignisse, Neustart/Reset und Quelltext-Anbindung der Berechtigung sowie UI-/Aufnahme-Trennung.
- Ausführung gegen die produktive `RecordingAudioMonitor`-Klasse mit lokalem Kotlin-Compiler und kleinem Testannotation-/Reflection-Runner, nicht Gradle/JUnit. Keine Mikrofonmesswerte vom Gerät simuliert oder als gemessen ausgegeben. Quelltext-Integrationsprüfungen ersetzen keine Laufzeitprüfung der Berechtigung oder des aufgenommenen Videos.
- Drei Android-Instrumentierungstests für die echte Canvas-Pegelanzeige ergänzt, **hier nicht ausgeführt**.
- Offizielle CameraX-1.4-API-Signaturen für `withAudioEnabled()`, `getAudioAmplitude()` und die Audiostatus-Konstanten geprüft. Kein Bibliotheksupgrade notwendig.
- **Nicht ausgeführt:** kompletter Gradle-/Android-Build, physische Mikrofon-/Kameraprüfung, MP4-Wiedergabe oder Audiotrack-Analyse, GPU-Aufnahme, Signierung.

## Gemini: Build und abschließende Abnahme

1. Lokale Änderungen sichern, `main` per Fast-forward übernehmen. **Den neuen Audio-Commit bauen, nicht nur ced11fd.** Version ist weiter 1.0.30 / Code 31, sofern nicht inzwischen veröffentlicht; bestehende Releases niemals überschreiben.
2. `gradlew.bat testDebugUnitTest assembleDebug` sowie `connectedDebugAndroidTest` auf verfügbarem Gerät/Emulator. Neue RecordingAudioMonitorTest und MicrophoneLevelViewTest mitprüfen, vorhandene Tests nicht abschwächen. Die vollständigen Prüfungen aus der Kamera-/Sichtfenster-Übergabe bleiben gültig.
3. Auf echtem Gerät Mikrofon zunächst ablehnen: kein Video startet, Foto bleibt möglich. Freigabe über erneuten Dialog/Einstellungen erteilen und Aufnahme durch Video-Tipp starten. App-Pause und Ansichtswechsel prüfen; kein nachträglicher Autostart. Keine Hintergrundaufnahme.
4. Zwei Clips mit Sprache aufnehmen, **Overlay AN und AUS**. Stimme muss im gespeicherten MP4 hörbar sein, synchron zum Video. Videodatei bei Bedarf zusätzlich mit MediaExtractor/ffprobe auf Audiotrack prüfen; ein Pegelausschlag allein beweist keine gespeicherte Tonspur.
5. Pegel schlägt bei Sprache aus und fällt bei Ruhe ab. Balken, dBFS-Anzeige, Schaltflächen und Android-Leisten dürfen in keinem der beiden Videos sichtbar sein. Foto mit/ohne Overlay kurz auf unveränderten Ausschnitt prüfen.
6. Mikrofon-Privatsphärenschalter/Systemstummschaltung während Recording testen: Warnung statt plausibel stehenbleibendem Pegel, vorhandenes Video bleibt gespeichert und bekommt einen Tonhinweis. Danach neue Aufnahme muss ohne alte Warnung starten. Sofortstopp/langer Clip/App-Pause müssen abspielbare Ergebnisse oder eine klare Fehlermeldung liefern.
7. Nach erfolgreichem Test mit bestehendem Schlüssel `assembleRelease`, Signatur/Updateinstallation und SHA-256 prüfen. Genau den getesteten Commit taggen und veröffentlichen. Keine eigene Kameralösung entwickeln; reproduzierte Fehler an ChatGPT zurückmelden. Tatsächlich verwendetes Testgerät und offene Prüfungen angeben.

Primärquellen:
- https://developer.android.com/reference/androidx/camera/video/AudioStats
- https://github.com/androidx/androidx/blob/androidx-main/camera/camera-video/api/1.4.0-beta03.txt
- https://developer.android.com/reference/androidx/camera/video/PendingRecording

Release Notes ergänzen: „Videoaufnahme mit Ton und Mikrofonpegel im Bedienpanel. Pegelanzeige wird nicht ins Video eingebrannt; Mikrofonfreigabe erst beim Aufnehmen.“
