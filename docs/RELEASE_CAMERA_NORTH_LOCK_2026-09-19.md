# Release-Übergabe: Nordreferenz ohne Drift bei Kreisgesten

Basis: `30ed997d0a43612790b0961e237e0f108b8abbcf` (1.0.28 / Code 29).
Diese Änderung ersetzt die freie Trackball-Rollung aus der vorigen Kamera-Übergabe. Zoom, physischer ISS-Maßstab, ISS-Drehpunkt, Culling und transparente Innenansicht bleiben erhalten.

## Ursache und Abgrenzung

Die bisherige `rotateScreen`-Funktion drehte um jeweils mitgeführte Bildschirmachsen und speicherte `localForward` und `localUp` als neue Basis. Solche aufeinanderfolgenden räumlichen Drehungen sind nicht vertauschbar. Eine geschlossene Fingerbewegung muss daher nicht zur ursprünglichen Orientierung zurückführen. Das erklärt die im Nutzer-Video zunehmende Verdrehung; es ist nicht bloß ein Rundungsfehler, den häufigeres Normalisieren beseitigen könnte.

Eine unabhängige Nachrechnung der alten Rodrigues-Formeln mit einem Kreis von 150 px Radius und 360 Segmenten ergab nach einer Runde rund 30.3 Grad Änderung des Up-Vektors und 11.6 Grad Änderung der Blickrichtung. Diese Zahlen beschreiben den synthetischen Kontrollfall, nicht eine Messung aus dem Video.

Grundprinzip von stabiler Orbitsteuerung gegenüber freier Trackballsteuerung:
- https://threejs.org/docs/pages/OrbitControls.html
- https://threejs.org/docs/pages/TrackballControls.html

Keine Three.js-Abhängigkeit eingeführt. Die vorhandene Kotlin-/Filament-Architektur bleibt bestehen.

## Änderung

- Schwenken und Neigen sind zwei unabhängige Winkel in Double-Präzision. Aus ihnen wird die Kamerabasis analytisch neu berechnet; keine Akkumulation von Rollrotationen.
- Die feste Hochreferenz ist die örtliche Nordtangente an der ISS. Nicht wieder global +Y einsetzen: Das würde die vorher beseitigte Südhalbkugel-Invertierung zurückbringen.
- Die lokale Nordrichtung hat in jeder erlaubten Kamerastellung eine positive Bildschirm-Hochkomponente und keine Bildschirm-Rechtskomponente. Das bedeutet nicht, dass der geografische Nordpol als Punkt an einer festen Bildschirmposition eingefroren wird: Perspektive, Kamerabahn und ISS-Bewegung bleiben wirksam.
- Eine geschlossene Einfingerbahn bei festgehaltenem Zeitpunkt kehrt zur Ausgangsausrichtung zurück. Auch wenn der Finger während derselben Geste kurz über eine Neigungsgrenze hinausgeht, wird dessen Gesamtweg nicht zwischendurch verworfen.
- Beim Loslassen, Fingerwechsel und Pausieren wird nur der nicht darstellbare Überhang verworfen. Die nächste Geste reagiert sofort. Auch die letzte Koordinate aus ACTION_UP wird verarbeitet. Es gibt keine zusätzliche nachträgliche Kamerarollung.
- Blick genau entlang der Hochreferenz besitzt keine eindeutige Nord-oben-Projektion. Deshalb endet die Neigung 0.5 Grad vor den beiden Singularitäten (effektive lokale Nord-Elevation -89.5 bis +89.5 Grad). Freie vertikale 360-Grad-Überschläge werden damit bewusst nicht mehr angeboten. Horizontales Umkreisen bleibt unbegrenzt. Nadir und darüber hinaus sind erreichbar; der senkrechte Erdblick ist keine dieser Singularitäten.
- Default bleibt 58 Grad vom Nadir / 32 Grad über der Tangente mit unverändertem Abstand. Ein Rückwärtsblick mit möglicher Erddurchquerung ist beispielsweise über yaw=180, pitch=-58 erreichbar.
- Keine Änderungen an Geometrie-/Skalierungskonstanten, Sternkoordinaten, Shadern, Materialpaketen, Flugbahnen, Wolken, AR-Betrachterkamera oder Zoomformeln.

## Lokale Prüfung

22 Kotlin/JVM-Testmethoden bestanden: 13 Rotations-/Gestenprüfungen und die 9 unveränderten Zoom-Regressionsprüfungen.

Abgedeckt:
- 480 geschlossene Kreise in beiden Richtungen, verschiedenen Radien und Segmentierungen, plus 100 Rechteckwege; Rückkehr zur Ausgangsausrichtung.
- 72000 gemischte Gestenposen mit laufender Prüfung der örtlichen Nordreferenz.
- Reset auf beiden Halbkugeln, frühere -32-Grad-Problemzone, Nadir/Weiterneigen, 720-Grad-Horizontaldrehungen, umgekehrte/unterteilte Diagonalbewegungen.
- Finite Neigungsgrenzen und direkte Reaktion der nächsten Geste nach Loslassen.
- Erlaubte Erddurchquerung, unveränderter ISS-Pivot und unveränderte Orientierung beim Zoomen.
- 280800 tatsächliche Zoomposen sowie vorhandene Culling-/Erdübersicht-/Pinch-Regressionsprüfungen.
- Quelltextprüfung der Gestengrenzen und der unveränderten Zoom-/Innenraum-Anbindung im Renderer.

Ausgeführt mit lokalem Kotlin-Compiler und kleinem Reflection-Runner samt Testannotation, nicht mit Gradle. Die Produktions-Geometrie ist echt, nicht durch Android-Testdoubles ersetzt. Renderer-Anbindung nur im Quelltext geprüft, keine Ausführung von Android MotionEvent oder Filament. **Kein vollständiger Android-Build, kein GPU-Test, kein Smartphone-Test und kein GLB-Test in dieser Runde.** Diese Prüfungen bleiben für die Release-Umgebung erforderlich.

Vorherige Tests, die ausdrücklich freie 720-Grad-Vertikalüberschläge und eine mitrotierende Bildschirm-Hochachse verlangten, wurden durch Nord-Lock-/Kreisgestentests ersetzt. Das ist eine geänderte Bedienungsanforderung, keine Abschwächung eines weiterhin gewünschten Merkmals.

Hash-Abgleich: ursprünglicher Renderer `b36c1191448828a3c58fe7e23af101adddea1deb`, unveränderte Produktionsgeometrie `0a1273f9d58d8663c51838bd95f360c0e067d4ea`, unveränderte Zoomtests `01fcde243949eec6f9424090515be0e01b0b3487`. Hochgeladene geänderte Dateien entsprechen den lokal geprüften Dateien.

## Gemini: nur Abnahme, Build und Release

1. Lokale Änderungen sichern, `main` per Fast-forward übernehmen. Keine alten ZIPs oder Kameravarianten darüberkopieren.
2. Wenn noch frei: Version 1.0.29 / Code 30 setzen, archivesName angleichen. Vorhandene Tags/Releases nicht überschreiben.
3. `gradlew.bat testDebugUnitTest assembleDebug` ausführen. Alle vorhandenen Tests einschließlich GLB-Hash prüfen; insbesondere OrbitRotationRegressionTest und OrbitZoomRegressionTest. Keine Tests abschwächen.
4. Auf Gerät/Emulator Zeitpunkt einfrieren, Kamera zurücksetzen und in Nah-/Fernansicht Kreise und Rechtecke in beiden Richtungen mit einem Finger ziehen. Am Ausgangspunkt darf keine Verdrehung übrigbleiben. Auch Kreise nahe der Neigungsgrenze testen. Loslassen/neu ansetzen muss sofort reagieren. Bei laufender ISS bewegt sich die Szene zusätzlich durch die Bahn; das nicht als Gestendrift bewerten.
5. Auf beiden Halbkugeln Reset, Links/Rechts, Blick zum Nadir und darüber hinaus, Neigungsgrenzen ohne Umkippen, Pinch/Ein-Finger-Wechsel, Erddurchquerung und Karten-/Flugbahnoptionen kurz prüfen. Testumgebung nennen. Nordpol als Kartenpunkt darf sich während echter Perspektivänderungen bewegen; die Kamera soll keine Rollung aufbauen.
6. Nach erfolgreicher Abnahme mit bisherigem Schlüssel Release-APK bauen, Signatur/Version/Updateinstallation prüfen, SHA-256 erstellen. Versionscommit pushen, exakt den gebauten Commit taggen und Release veröffentlichen.
7. Bei reproduzierbarem Fehler konkret an ChatGPT zurückmelden, keine eigene Kameraneuentwicklung. Keine Shader-Neukompilierung erforderlich.

Release Notes: Kreisende Wischbewegungen verdrehen die Kamera nicht mehr; stabile örtliche Nordreferenz; Zoom, Maßstab und ISS-Drehpunkt unverändert.
