# Release-Übergabe: Zoom-Korrektur nach 1.0.26

Basis: `f0fade15be83f70e2543293c6a2e5f50bafcd6f4` (1.0.26 / Code 27).
Diese Übergabe ersetzt für die Kameraführung die vorherige Orbit-/Flugbahn-Übergabe.

## Reproduzierte Ursachen

1. Der vorige Controller verschob den Orbitanker beim Herauszoomen von der ISS durch das Erdinnere zum Erdmittelpunkt. Seine Kollisionskorrektur setzte die Kamera anschließend auf eine Kugel nur 150 m über der Oberfläche. Beispiel mit Radius der ISS-Position 10.666, Breite 22.06°, Länge 56.17°, Aspect 0.54, Default-Winkeln: Bei Zoom 4 lag die Kamera noch etwa 430 km hoch, bei Zoom 5 nur noch 0.15 km. Das geschah trotz steigender nomineller Kameradistanz. Die bisherigen Tests prüften überwiegend diese nominelle Distanz, nicht den tatsächlichen Kamerapfad.
2. Nahbereich und ferne Himmelskugel wurden für das Frustum-Culling in einem extremen Bereich zusammengefasst. Bei `near` ungefähr 0.000049 und `far=5000` rundet der GL-Projektionskoeffizient `(far+near)/(near-far)` als Float auf exakt -1. Filament 1.75.1 extrahiert und normalisiert die Culling-Ebenen in Float. Die Far-Normale kann dadurch null/ungültig oder instabil werden. Eine achsenparallele Gegenprobe reproduziert nicht-endliche Ebenen. Das kann ganze Renderables verschwinden lassen, ohne dass sich ihre Geometrie geändert hat.

Primärquellen der verwendeten Filament-Version:
- https://github.com/google/filament/blob/v1.75.1/filament/src/Frustum.cpp
- https://github.com/google/filament/blob/v1.75.1/filament/src/details/Camera.cpp
- https://github.com/google/filament/blob/v1.75.1/android/filament-android/src/main/java/com/google/android/filament/Camera.java

## Umsetzung

- Die ISS bleibt in allen Zoomstufen der geometrische Drehpunkt und liegt auf der optischen Achse. Keine Verlagerung zur Erdmitte, kein in der Erde liegender Übergangsanker und keine nachträgliche radiale Kamera-Teleportation.
- Kamerarichtung und Drehpunkt hängen nicht vom Zoom ab. Die Entfernung zur ISS wächst kontinuierlich; Pinch verändert die physische Entfernung mit gleichem Verhältnis bei jedem Zoom, nicht den logarithmischen Steuerparameter durch Division.
- Die Kamera umkreist die ISS auf der raumseitigen Halbkugel: Azimut 360°, Elevation 0° (Seitenansicht) bis 85° über der örtlichen Tangentialebene. Damit nehmen bei geradlinigem Herauszoomen sowohl ISS-Abstand als auch Erdabstand zu. Dieser Schutz begrenzt die Drehung, statt bei Kollisionen den Kamerastandort oder das Blickziel zu versetzen.
- Default bleibt 32° über der Tangentialebene / 58° vom Nadir und etwa 1 km Kameradistanz im Hochformat. Die echte 109-m-Skalierung bleibt unverändert. Die Station liegt jetzt mittig statt mit einem zoomabhängigen Versatz im Bild.
- Maximales Herauszoomen zeigt die Erde vollständig mit höchstens etwa halber Viewport-Ausdehnung. Da die ISS und NICHT die Erde der Drehpunkt bleibt, ist die Erde nicht zwangsläufig exakt zentriert. Im Querformat wird auch die kleinere Höhe berücksichtigt.
- Bei der Nahkamera wird die Look-at-Richtung mit einem ausreichend langen Vektor übergeben; in der Ferne direkt mit der ISS-Position. Beide Ausdrücke treffen sich bei Abstand 1 und beschreiben dieselbe Blickrichtung.
- Culling-Fernbereich jetzt abhängig von der Kamera: mindestens 32 Welteinheiten, ansonsten Kameraabstand zur Erdmitte + 20. Er enthält Erde und ISS, ohne die Float-Ebenen im Nahbereich degenerieren zu lassen. Filament benutzt für das tatsächliche Rendering eine unendliche Fernprojektion. Die Himmels-Renderables haben bereits `culling(false)` und bleiben deshalb sichtbar, obwohl ihr Abstand größer als die lokale Culling-Grenze ist.
- Near wird unter Berücksichtigung des ganzen ISS-Modellradius gewählt. Modell und Erdoberfläche bleiben vor der Nahgrenze.

Keine Änderungen an Erd-/ISS-Maßstab, Flugbahnen, Sternkoordinaten, AR-Kalibrierung, Wolkenquellen, Shaderquellen oder kompilierten Filament-Materialien.

## Tatsächlich ausgeführte lokale Prüfungen

Die Originaldateien für Controller, Geometrie, Renderer und bestehende Szenentests wurden vor Änderungen anhand ihrer Git-Blob-Hashes abgeglichen.

23 Kotlin/JVM-Testmethoden bestanden: 14 bestehende Szenentests und 9 neue Zoom-Regressionsmethoden. Der bisherige Test der auf die Erde zentrierten Übersicht wurde gezielt auf den nun geforderten ISS-Drehpunkt und die echte Off-Axis-Kugelsilhouette umgestellt.

Die neuen Prüfungen umfassen:
- 168480 tatsächliche Kameraposen: ISS-Abstand, Erdabstand und konstante Blickrichtung über den gesamten Zoomweg;
- 4320 Posen mit festem ISS-Drehpunkt und Nord-up;
- 4320 Float-Frusta mit endlichen Ebenen und sichtbarer ISS;
- 720 vollständige, auch außerhalb der Bildmitte liegende Erdsilhouetten am maximalen Zoom;
- Sichtbarkeit der Earth-AABB in der Default-Nahansicht und über den Zoomweg;
- gleiche Pinch-Verhältnisse, Hin-/Rückzoom, Reset und ungültige Eingaben;
- reproduzierter negativer Kontrollfall mit der alten `far=5000`-Konfiguration;
- Quelltext-Integrationsprüfung der tatsächlich verwendeten Far-/Pinch-Funktionen und des deaktivierten Sky-Cullings.

Ausführung mit lokalem Kotlin-Compiler und kleinem Test-Runner/Annotation statt Gradle/JUnit. Die Geometrieklassen und der Trajektorien-/Topocentric-Code sind Produktionscode, keine Android-Testdoubles. Der unveränderte `OrbitState`-Datentyp wurde für den lokalen Lauf aus seiner Quelldatei übernommen. Die Sampler-Tests verwenden ihre ohnehin vorgesehene injizierte Zustandsfunktion.

Ein vorhandener Test (`measuredBoundsBelongToBundledModel`) wurde lokal übersprungen, weil die binäre GLB nicht in diesem lokalen Testverzeichnis vorlag. Er ist unverändert im Repository und muss im vollständigen Checkout laufen. Kein Android-APK-Build, kein GPU-Test und kein physischer Smartphone-Test wurden hier ausgeführt.

## Auftrag an Gemini: Build, Gerätetest und Release

1. Lokale Änderungen erhalten, `main` per Fast-forward aktualisieren. Diesen Fix übernehmen; keine Kamera-, Shader- oder Maßstabsänderungen aus älteren Arbeitsständen zurückkopieren.
2. Wenn noch frei: `versionName=1.0.27`, `versionCode=28`, `archivesName` passend. Vorhandene Tags/Releases nicht überschreiben.
3. `gradlew.bat testDebugUnitTest assembleDebug`, insbesondere OrbitSceneRegressionTest und OrbitZoomRegressionTest. Den GLB-Hash-Test im vollständigen Checkout ausführen. Keine Tests abschwächen.
4. Auf dem Gerät/Emulator dieselbe eingefrorene Szene ohne Kameradrehung von minimalem bis maximalem Zoom durchfahren und zurück. Erde darf nicht verschwinden, plötzlich näherkommen oder auf die Oberfläche springen. Anschließend bei Nah-, Mittel- und Fernzoom drehen: ISS bleibt Drehpunkt, Seitenansicht ist erreichbar, kein Durchfahren der Erde. Hoch-/Querformat sowie Reset prüfen. Bei absichtlicher Kameradrehung kann sich der sichtbare Erd-Ausschnitt natürlich ändern.
5. Sterne/Atmosphäre und beide Flugbahnen kurz auf Regressionen prüfen. Die kürzere Culling-Fernbereichsgrenze darf den nicht-gecullten Sternhimmel nicht entfernen. Keine neue Materialkompilierung nötig.
6. Mit dem bisherigen Schlüssel Release-APK bauen, Signatur/Updateinstallation/Version prüfen und SHA-256 erstellen. Versionscommit pushen, genau den geprüften Commit taggen, Release mit APK und Prüfsumme veröffentlichen. Bei reproduziertem Build-/Gerätefehler den konkreten Fehler melden, nicht trotzdem veröffentlichen.

Release Notes: Kontinuierlicher ISS-zentrierter Zoom; Verschwinden der Erde im Nahbereich korrigiert; gleichmäßige Pinch-Reaktion. Keine neue Wolken- oder AR-Funktion behaupten. Tatsächliche Testumgebung nennen.
