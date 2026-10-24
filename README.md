# Schematic Builder (mod Fabric, Minecraft 1.21.11)

## Budowanie
1. Skopiuj `gradlew`, `gradlew.bat` i `gradle/wrapper/` z dowolnego projektu Fabric pod 1.21.11
   (np. z fabric-example-mod), albo zainstaluj Gradle 9.x i uzyj `gradle build`. Java 21.
2. `./gradlew build` -> `build/libs/schematic-builder-1.0.0.jar`
3. Wrzuc jar do `mods` (wymaga Fabric API).

## Uzycie
Wrzuc plik `.litematic` do `.minecraft/schematics/`.

    /schem load <nazwa>      wczytaj schemat (origin = blok pod toba)
    /schem origin            ustaw origin na swoja pozycje
    /schem origin <x> <y> <z>
    /schem start | stop | status
    /schem range <1-5>       zasieg (domyslnie 4)
    /schem speed <1-10>      bloki na tick (domyslnie 1)
    /schem delay <0-20>      przerwa w tickach (domyslnie 1)

Materialy trzymaj w ekwipunku. Chodz po budowie recznie.
