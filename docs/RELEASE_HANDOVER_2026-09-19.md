# Release-Uebergabe: UI und AR-Projektion, 19.09.2026

## Basis und Umfang

Gepruefter Ausgangsstand: `3aea77f86a4096f23c993ad66adf77916481e9e4`.

Die Ansichtsauswahl schaltet jetzt durch Karte (Blue Marble mit Wolken), Wolken aus (Blue Marble ohne Wolken), Satellit und Satellit-Referenz. Die separaten Schaltflaechen fuer Wolken und Normalenanalyse sind entfernt. Entwicklerdiagnosen per ADB bleiben erhalten. Fuer die Reihenfolge zaehlt die angeforderte Quelle, nicht eine voruebergehend verwendete Fallback-Textur.

Im AR-Projektor wurden die Richtung der Sensor-/Displayrotation, der bisher ignorierte optische Mittelpunkt und Skew sowie die abweichende Horizontprojektion korrigiert. Der Horizont verwendet jetzt dieselbe Pixelprojektion wie die ISS. Kamera-Starts werden nach Abbruch nicht nachtraeglich ausgefuehrt; Vorschautransformationen werden aktualisiert, Zoom-Observer beim Stoppen entfernt. Ohne Rueckkamera wird nicht stillschweigend ein ungeeignetes Selfie-Kameramodell verwendet.

Keine Aenderungen an Erdkoordinaten, Shaderquellen, kompilierten Filament-Materialien oder Wolkenquellen.

## Tatsaechlich ausgefuehrte Pruefungen

- Originaldateien MainActivity, Layout und CameraProjectionModel anhand der Git-Blob-Hashes mit dem gelesenen Repository-Stand abgeglichen.
- XML geparst und ViewBinding-Referenzen gegen die Layout-IDs geprueft.
- 20 Kotlin/JVM-Pruefungen bestanden: neun bestehende Kamera-Tests, sieben neue Kamera-Regressionsfaelle und vier neue Ansichts-Auswahltests.
- Die lokale Ausfuehrung verwendete affine Android-Testdoubles und einen kleinen JUnit-kompatiblen Aufrufer, KEIN Android SDK und KEIN Smartphone. Das ist kein APK-Build- oder Hardware-Nachweis.
- Gegen den unveraenderten alten Projektor scheiterten fuenf der sieben neuen Kamera-Regressionsfaelle (Sensorrichtung, optisches Zentrum/Skew, Displayrotation, Horizontkonsistenz, Horizont-Sichtbarkeit).

Die neuen Kamera-Tests liegen unter `app/src/androidTest`, damit Gemini sie nochmals mit der echten Android-Matrix ausfuehren kann. Die Auswahltests liegen unter `app/src/test`.

## Verbleibende Genauigkeitsgrenzen

Kameramodell und Kompass brauchen einen physischen Geraetetest. Linsenverzeichnung, optische Achsenabweichungen, elektronische Stabilisierung und Wechsel zwischen physischen Objektiven sind nicht vollstaendig kalibriert. Verfuegbare Brennweiten werden nicht mehr anhand eines willkuerlichen Millimeterbereichs als aktive Linse geraten. Unpassende Intrinsics-Pixelraster werden nicht mit der Preview-Matrix vermischt; gegebenenfalls wird eine gekennzeichnete Naeherung verwendet. Bei Zoom auf dem Geraet kontrollieren, welche Beschneidung die tatsaechliche CameraX-Transformation bereits enthaelt; keine doppelte Skalierung.

## Auftrag an Gemini

1. Arbeitsverzeichnis auf ungesicherte Aenderungen pruefen. `main` per Fast-forward aktualisieren, keine fremden Aenderungen verwerfen.
2. Aktuelle Version ist noch 1.0.24 / Code 25. Wenn unbenutzt, fuer dieses Release 1.0.25 / Code 26 setzen und `archivesName` angleichen. Vorhandene Tags/Releases nicht ueberschreiben.
3. `gradlew testDebugUnitTest assembleDebug` ausfuehren. `connectedDebugAndroidTest` mit dem neuen `CameraProjectionRegressionTest` ausfuehren, sofern ein Geraet/Emulator bereitsteht; fehlende Hardwarepruefung offen benennen.
4. Auf einem physischen Geraet die vier Ansichten, fehlende Zusatzwolken auf VIIRS, AR-Schwenken/Kippen, Randpositionen, Zoom sowie schnelle Wechsel Kamera/virtueller Himmel/ISS und Pause/Resume pruefen. Kameraerlaubnis nach Ablehnung erneut erteilen und den Start testen.
5. Mit dem bisherigen Release-Schluessel bauen; keine neuen Schluessel erzeugen. APK-Signatur, Version und Updateinstallation pruefen, SHA-256 dokumentieren. Keine Passwoerter, Keystores oder Tokens hochladen.
6. Versionsaenderung committen/pushen. Getesteten Release-Commit taggen und GitHub Release mit signierter APK und Pruefsumme veroeffentlichen. Bei Build-/Testfehlern nicht als erfolgreich veroeffentlichen.

Keine neue Renderer- oder Wolkenpipeline beginnen. Nur reproduzierte Build-/Funktionsfehler beheben und danach abschliessen.
