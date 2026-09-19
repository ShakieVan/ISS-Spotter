# Release: Sternhimmel, ISS-Massstab, Zoom und Flugbahnen

Basis: `dd7a9d3d19752d366337d9db801c6a39c63be943` (1.0.25 / Code 26).
Diese Uebergabe ersetzt fuer dieses Release die vorherige UI/AR-Uebergabe.

## Aenderungen

- Orbit-Sternhimmel: Die vorhandenen Katalogmeshes benutzen +Z fuer zunehmende Rektaszension, die Erdkoordinaten aber -Z fuer Osten. Eine gemeinsame Transformation korrigiert diese Spiegelung und beruecksichtigt GMST zur Snapshot-Zeit. Sterne, Verbindungslinien und Labels stimmen wieder ueberein. Die Himmelskugel folgt nur der Kameratranslation, nicht ihrer Drehung; dadurch keine kuenstliche Parallaxe und kein Verlassen der Himmelskugel beim Zoom-Out. Nord-up wird nach der Kameradrehung erneut aus +Y bestimmt.
- ISS: Aus den POSITION-Accessors der wirklich dargestellten polySurfa1/2/3-Meshes wurde eine groesste Ausdehnung von 45.544363021850586 Modell-Einheiten ermittelt. Bisherige Skalierung 0.009 ergab bei R_Erde=10 entsprechend 6371 km eine ISS-Spannweite von 261.146823 km. Jetzt einheitlicher Faktor 0.000003756507501176603 fuer 109 m; Modell um seinen gemessenen Mittelpunkt zentriert. Keine perspektivisch kaschierte Vergroesserung.
- Standardkamera: passende Naehe zur jetzt kleinen Station, im Hochformat etwa 1 km; Abstand aus Bildformat und 109-m-Spannweite abgeleitet. Default-Blick 58 Grad vom Nadir fuer Erde und Horizont. Dynamische Near-Clipping-Ebene, damit das kleine Modell nicht abgeschnitten wird.
- Zoom: logarithmischer Abstand, Zoomparameter 0.35 bis 12. Beim Herauszoomen wird das Blickziel stetig von der ISS zur Erdmitte verschoben. Am Anschlag betraegt der Erddurchmesser 48 Prozent der Viewport-Breite (bei normalen Bildschirmformaten). Das optische FOV bleibt 42 Grad. Die Kamera wird nicht in die Erde gesetzt. Bei winziger ISS zeigt die eingeschaltete Flugbahn eine symbolische ISS-Markierung statt eines kuenstlich grossen Modells.
- Flugbahnen: in beiden Ansichten separat schaltbar und gespeichert. Erde: Bodenspur +/-45 Minuten. Betrachter/AR: Himmelsbahn +/-10 Minuten mit demselben Kameraprojektor wie die ISS-Markierung. Vergangenheit durchgehend, Zukunft gestrichelt; beide zeitlich zur Aussengrenze auf null ausgeblendet. Der aktuelle Punkt wird exakt aus dem aktuellen Snapshot eingesetzt. Sampling alle 5 Sekunden, Cache-Erneuerung etwa alle 5 Sekunden auf einem Worker. Keine komplette Bahnpropagation in der Zeichenroutine. Rueckseite der Erde und Kameraebene werden nicht durch falsche Verbindungssegmente ueberbrueckt. Unter dem Horizont ist die AR-Bahn wie der bestehende Zielindikator bernsteinfarben: eine Richtungsanzeige, kein Sichtbarkeitsversprechen.

Atmosphaere, Wolken-/Satellitendaten und die zuletzt korrigierte AR-Kalibrierung bleiben erhalten. Keine Shaderquellen, kompilierten Materialpakete oder GLB-Dateien wurden geaendert.

## Nachrechnung und Quellen

Details: `docs/iss-model-bounds.json` und `OrbitScale`.
Gemessener GLB-Git-Blob: `884cdf03d96b28285c1cdfdd34fcfd4f5de0021d`.
Ausgeblendete bended*/pCyl*-Meshes wurden nicht in die ISS-Spannweite eingerechnet.

- NASA, ISS dimensions: https://www.nasa.gov/international-space-station/space-station-facts-and-figures/
- USNO, sidereal-time reference: https://aa.usno.navy.mil/faq/GAST

Der Bahnberechner Sgp4Propagator wurde nicht ausgetauscht. Die Linien sind aus den aktuellen Bahnelementen rekonstruierte/vorausberechnete Positionen, kein historisches GPS-Protokoll. Ihre Genauigkeit bleibt die Genauigkeit des vorhandenen vereinfachten Orbitmodells. Der Kataloghimmel ist eine GMST-basierte Naeherung ohne hochpraezise Praezession/Nutation/Eigenbewegung. Die bisherige Milchstrassentextur ist keine neu astrometrisch kalibrierte Himmelskarte.

## Tatsaechlich ausgefuehrte lokale Tests

14 neue Kotlin/JVM-Testmethoden aus OrbitSceneRegressionTest bestanden. Darunter:

- physischer ISS-Massstab und Zentrierung;
- Kamerasichtbarkeit und Nord-up in 1260 Kombinationen;
- Erddurchmesser 48 Prozent in 72 Kombinationen aus sechs Bildformaten, Breite und Blickrichtung;
- monotone Zoom-Distanz in 1166 Schritten, Reset und NaN-Behandlung;
- Himmelsparitaet und Vergleich mit unabhaengiger Stundenwinkel-/ENU-Projektion;
- Himmelsmatrix/Labelkonsistenz ohne Translationsparallaxe;
- Trajektoriensampling, exakter aktueller Anschluss, Vergangenheit/Zukunft und Fade-Enden;
- Viewport-Clipping, Datumsgrenze, Erd-Rueckseite.

Zusaetzlich bestehende Kamera-Drag-Richtungsbedingungen nachgerechnet; XML geparst, eindeutige IDs und beide getrennten Flugbahn-Schalter geprueft. Die lokale Ausfuehrung nutzte einen kleinen Test-Runner statt Gradle und fuer den Samplingtest eine einfache injizierte Zustandsfunktion mit der vorhandenen WGS84-TopocentricPosition.

Der zusaetzliche Test measuredBoundsBelongToBundledModel ist im Repository enthalten, wurde lokal aber nicht ausgefuehrt, weil hier nur die ausgewerteten GLB-Metadaten vorlagen. Er muss im vollstaendigen Checkout laufen. Kein Android-SDK-/APK-Build, kein GPU-Rendering und kein Smartphone-Test wurden hier ausgefuehrt.

## Auftrag an Gemini: nur Build, Abnahme und Release

1. Lokale Aenderungen sichern, `git pull --ff-only` auf main. Keine Dateien aus einem alten Arbeitsstand zurueckkopieren.
2. Falls noch unbenutzt: versionName 1.0.26, versionCode 27, archivesName passend setzen. Vorhandene Releases/Tags nicht ueberschreiben.
3. `gradlew.bat testDebugUnitTest assembleDebug` ausfuehren; insbesondere OrbitSceneRegressionTest und bestehende Kamera-Tests. Bestehende Android-Instrumentierungstests ebenfalls ausfuehren, soweit Testgeraet vorhanden. Keine Tests abschwaechen.
4. Auf Geraet/Emulator: Reset-Nahansicht mit sichtbarer kleiner ISS, Seitenansicht, maximaler Zoom-Out (Erde zentriert etwa halbe Breite), Rueckzoomen, Drehen ohne Eindringen in die Erde, Sternlinien/Labels. Das kleine Modell darf nicht durch die Near-Ebene verschwinden.
5. Beide Flugbahn-Schalter unabhaengig ein/aus, Neustart/Persistenz, durchgezogene Vergangenheit/gestrichelte Zukunft und Ausblenden beider Enden pruefen. In AR muss die Bahn beim Schwenken an der ISS-Markierung haften. Die Bodenspur darf nicht ueber die unsichtbare Erd-Rueckseite gezeichnet werden. Zusaetzlich Flugbahn bei Sprung der Debug-Zeit/Standortaenderung und beim Wechsel der Ansichten pruefen.
6. Mit dem bisherigen Release-Schluessel `assembleRelease`, APK-Signatur, Version und Updateinstallation pruefen. SHA-256 erstellen. Keine Schluessel/Passwoerter/Tokens hochladen.
7. Versionscommit pushen, exakt den geprueften Commit taggen und Release mit signierter APK und Pruefsumme veroeffentlichen. Testumgebung und nicht ausgefuehrte Hardwaretests ehrlich nennen.

Bei einem reproduzierten Build-/Funktionsfehler den konkreten Fehler melden; keine neue Rendering-Architektur oder Wolkenquelle anfangen. Die Entwicklung liegt bei ChatGPT, Gemini uebernimmt die Release-Erzeugung.
