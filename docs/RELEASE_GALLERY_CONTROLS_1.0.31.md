# Release 1.0.31: Galerie/Album und unverdeckte Kameraschaltflächen

Basis: `9006c22aae39b47f82c8dc0ef0ebd095ea27b7c6`. Während der Prüfung existierte bereits der Tag v1.0.30; deshalb ist jetzt **1.0.31 / versionCode 32** vorbereitet. Vorherige Ton-, Aufnahme- und Sichtfensterfunktionen bleiben erhalten. Kein alter Patch und keine erneute Kamera-/Shaderentwicklung erforderlich.

## Galerie öffnen

- Die Schaltfläche heißt jetzt „Galerie öffnen“. Samsung-Galerie (`com.sec.android.gallery3d`) wird bevorzugt. Der Knopf funktioniert auch vor einer neuen Aufnahme beziehungsweise nach einem App-Neustart; er hängt nicht mehr von einer nur im Arbeitsspeicher vorhandenen letzten Datei ab.
- Für das Album wird die tatsächliche MediaStore-BUCKET_ID einer zugänglichen, abgeschlossenen ISS-Aufnahme gelesen, nicht aus einem geratenen Pfad berechnet. Metadatenabfragen laufen im Hintergrund und beschränken sich auf die bekannten Aufnahmeordner; ab Android 10 zusätzlich auf die eigenen MediaStore-Einträge. Unfertige/gelöschte Medien werden ausgeschlossen, soweit die API das unterstützt. Bei fehlendem Zugriff wird keine zusätzliche umfassende Medienfreigabe verlangt.
- Der Album-Aufruf verwendet die in AOSP/Gallery2 belegte ACTION_VIEW-Konvention mit `bucketId` und `mediaTypes=5` (Bilder plus Videos). Zuerst wird er an Samsung gerichtet. **Das ist keine von Samsung zugesicherte, universelle Album-API.** Ob die konkrete One-UI-Galerie das gewünschte Album anzeigt oder die Zusatzparameter ignoriert, muss auf dem Galaxy getestet werden. Eine erfolgreich gestartete Activity beweist nicht, dass der Albumfilter tatsächlich übernommen wurde.
- Kann der Albumaufruf nicht gestartet werden, wird die Samsung-Startseite versucht; danach eine verfügbare andere Galerie. Nur als letzter Ersatz dient eine einzelne Aufnahme. Bei der Startseite erscheint ein Hinweis auf das Album ISS-Spotter. Ein langer Druck auf „Galerie öffnen“ umgeht den Albumaufruf und öffnet direkt die Galerie-Startseite, auch wenn eine Galerie den Albumaufruf zwar annimmt, intern aber nicht richtig auswertet.
- Keine fest codierte private Samsung-Activity, kein Zugriff auf interne Galerie-Datenbanken. Die Manifest-Ergänzung macht nur das eine Samsung-Paket für die Launcher-Abfrage sichtbar, nicht alle installierten Apps. Keine Zugriffsfreigabe auf die ganze MediaStore-Sammlung; nur der Einzeldatei-Ersatz erhält Leserecht auf genau diese URI.
- Während Foto, Video, Finalisierung oder einer ausstehenden Aufnahmeberechtigung bleibt der Galerieknopf gesperrt. Ein später fertiggestellter Metadatenabruf darf nach einem Ansichtswechsel oder Aufnahmestart keine fremde Activity mehr öffnen.

## Ein gemeinsames Aufnahmealbum

Neue Fotos und Videos werden beide unter `DCIM/ISS-Spotter/` abgelegt. Vorher waren sie auf Pictures/ISS-Spotter und Movies/ISS-Spotter verteilt, was zwei gleich benannte Alben erzeugen konnte. DCIM unterstützt sowohl Bild- als auch Videomedien.

Vorhandene Dateien werden NICHT verschoben, umbenannt oder gelöscht. Solange noch kein neues gemeinsames Album vorhanden ist, kann die Galerie-Navigation auf einen der bisherigen Ordner zurückgreifen. Sobald neue Aufnahmen existieren, wird das gemeinsame DCIM-Album bevorzugt; alte Alben bleiben separat. Bei Android 9 wird der gemeinsame Pfad mit der bereits vorhandenen Speicherfreigabe explizit gesetzt, bei Android 10+ über RELATIVE_PATH. Ordnerfehler beim Videostart werden im vorhandenen Fehlerpfad behandelt.

## Abgeschnittene Schaltflächen

Die wiederverwendeten Schaltflächen „Flugbahn“ und „Sternenhimmel“ wurden bisher in feste 40-dp-Zeilen gesetzt. Geerbte Text-/Font-Padding-Werte und die Baseline-Ausrichtung einer LinearLayout-Zeile wurden dabei nicht vereinheitlicht.

Jetzt gibt es einen gemeinsamen Layouthelfer: content-basierte Höhe (WRAP_CONTENT), mindestens 48 dp, vollständiges Font-Padding, vertikaler Innenabstand, mehr Platz am oberen Panelrahmen sowie deaktivierte Baseline-Verschiebung in den horizontalen Reihen. Text darf umbrechen statt am Rand abgeschnitten zu werden. Die vorhandenen Click-/LongClick-Listener der umgesetzten Modusschalter bleiben bestehen. Auch „Galerie öffnen“ und die Aufnahmebuttons erhalten passende flexible Höhen.

Bedienpanel und Mikrofonpegel bleiben Geschwister der AR-Zeichenebene und werden weiterhin nicht in Foto/Video gezeichnet. Kein Eingriff in Aufnahme-Overlaytransform, Tonpfad, Kameraprojektion, Objektivwechsel, ISS-Orbit, Nord-Lock, Zoom, Flugbahnen, Shader oder Materialpakete.

## Tatsächlich ausgeführte Prüfungen

- Originale ObserverCameraControls, ArCaptureSession und Manifest anhand ihrer Git-Blob-Hashes mit dem gelesenen Ausgangsstand abgeglichen. Hochgeladene neun Kotlin-/Manifest-/Testdateien ebenfalls mit den lokalen Dateien abgeglichen.
- **10 lokale Kotlin/JVM-Testmethoden bestanden**, gegen die produktive GalleryOpenPolicy/CaptureAlbumSpec: Startreihenfolge, Samsung-Präferenz, fehlendes Album, leere Bibliothek, Home-Umgehung, signierte Bucket-IDs, ungültige IDs/Dateinamen, gemeinsamer Aufnahmeordner sowie Quelltext-Anbindung/Erhalt von Ton und Overlay-Trennung. Lokaler Kotlin-Compiler mit kleinem Testannotation-/Reflection-Runner, nicht Gradle/JUnit.
- Manifest als XML geparst. Aufnahme-Diff beschränkt sich auf gemeinsame Metadaten/Ordnerfunktion und deren Fehlerbehandlung. Kein Audio- oder Overlay-Code ersetzt.
- Fünf Android-Instrumentierungstests ergänzt (GalleryControlsTest): Album-Intent, keine Sammlungsfreigabe, gemeinsamer RELATIVE_PATH, reale Font-/Layoutmetriken bei 100/130/200 Prozent Schriftgröße, Erhalt vorhandener Listener. **Hier nicht ausgeführt.**
- **Nicht ausgeführt:** vollständiger Gradle-/Android-Build, tatsächliche Samsung-Galerie-Navigation, gerenderte Ansicht auf dem Nutzertelefon, neue Galerie-Dateien/Medienaufnahme. Insbesondere keine Garantie des One-UI-Albumfilters aus den lokalen Tests ableiten.

## Gemini: Abnahme und Release

1. Lokale Änderungen erhalten, main per Fast-forward übernehmen. Version ist 1.0.31 / Code 32, nicht nochmals erhöhen. Vorhandene Tags/Releases nicht überschreiben.
2. `gradlew.bat testDebugUnitTest assembleDebug` und auf verfügbarem Gerät `connectedDebugAndroidTest` ausführen. Neue GalleryOpenPolicyTest und GalleryControlsTest sowie alle bestehenden Aufnahme-, Audio-, Kamera- und Orbit-Tests beibehalten.
3. Samsung-Gerät: Foto und Video aufnehmen; beide müssen als gemeinsame Gruppe im Album ISS-Spotter unter DCIM liegen. „Galerie öffnen“ muss bevorzugt die Samsung-Galerie starten. Konkret berichten, ob sie DIREKT das Album öffnet oder nur die Startseite; das nicht aus einem erfolgreichen Intent ableiten. Video-only-Album, App-Neustart, leeres/gelöschtes Album und langer Druck zur Startseite prüfen. Auf einem Gerät ohne Samsung müssen Ersatzaufrufe funktionieren oder eine klare Meldung liefern. Bestehende Aufnahmen dürfen nicht verschoben worden sein.
4. Betrachter-Bedienpanel bei normaler und vergrößerter Android-Schrift sowie schmaler Breite prüfen: „Flugbahn“/„Sternenhimmel“ einschließlich Glyphen und oberem Rand vollständig sichtbar; Schalter weiterhin bedienbar. Galerieknopf bei laufender Aufnahme gesperrt. Video mit Ton und Overlay AN/AUS stichprobenartig prüfen: Pegel und Panel bleiben außerhalb des gespeicherten Bildes.
5. Nach erfolgreicher Abnahme mit bisherigem Schlüssel Release-APK bauen, Signatur/Updateinstallation prüfen, SHA-256 erstellen und genau den geprüften Commit taggen/veröffentlichen. Keine Shader-Neukompilierung. Ein von One UI ignorierter Albumfilter ist eine dokumentierte Kompatibilitätsgrenze, kein Anlass, private Activity-Namen zu erraten. Konkrete Fehler mit Logs/Screenshot an ChatGPT zurückmelden.

## Referenzen

- https://developer.android.com/training/data-storage/shared/media
- https://developer.android.com/reference/android/content/Intent#CATEGORY_APP_GALLERY
- https://developer.android.com/training/package-visibility/declaring
- https://developer.android.com/reference/android/widget/TextView#setIncludeFontPadding(boolean)
- https://github.com/LineageOS/android_packages_apps_Gallery2/blob/lineage-22.2/src/com/android/gallery3d/data/LocalSource.java
- https://github.com/LineageOS/android_packages_apps_Gallery2/blob/lineage-22.2/src/com/android/gallery3d/app/GalleryActivity.java

Release Notes: „Galerieknopf bevorzugt Samsung-Galerie mit Albumanfrage; neue Fotos und Videos gemeinsam unter DCIM/ISS-Spotter; abgeschnittene Schaltflächen im Betrachter-Bedienpanel korrigiert. Direkter Albumaufruf ist galerieabhängig. Bestehende Aufnahmen bleiben unverändert.“
