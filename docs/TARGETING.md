# Why Minecraft 1.21.11

The newest stable Minecraft at the time of writing is **26.2**, and Mandela targets **1.21.11**
instead. That is not conservatism, it is the only option, and the reason is worth recording so the
decision can be revisited rather than re-derived.

## What was checked

| | 26.2 | 1.21.11 |
| --- | --- | --- |
| Mojang official mappings published | **no** | yes |
| Yarn mappings published | **no** | yes (`1.21.11+build.6`) |
| Fabric API build | yes (`0.158.0+26.2`) | yes (`0.141.6+1.21.11`) |
| Required Java | 25 | 21 |

Verified against `piston-meta.mojang.com` and `meta.fabricmc.net`, not from memory.

## Why that settles it

A mod is compiled against named classes and methods and then remapped to the obfuscated names the
game actually ships. Without a mapping set there is nothing to remap *to*: `LevelRenderer` cannot be
resolved to whatever it is called in the shipped jar. 26.2 publishes no `client_mappings` artifact and
has no Yarn build, so the toolchain has nothing to work with — this is not a Mandela limitation, it
is a property of that version right now.

Fabric API having a 26.2 build does not change this. That is built inside the Fabric project against
mappings that have not been published for general use.

## Revisiting

Retarget when either mapping set appears for a newer version. The change is `minecraft_version` in
`gradle.properties`, plus a matching `fabric_api_version`.

Two things will need attention when that happens, and the build will tell you about both:

1. `./gradlew verifyMixinTargets` fails loudly for any hook whose vanilla method was renamed. That is
   the point of it — the alternative is a mod that loads and quietly does nothing.
2. If the required Java version moves to 25, `java_version` in `gradle.properties` and
   `compatibilityLevel` in `mandela.mixins.json` both need raising.

Nothing in `core/` is version-specific. It has no Minecraft dependency at all, so a version bump is
confined to the `fabric/` module.
