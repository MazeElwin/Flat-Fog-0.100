# Flat Fog 0.100

A volumetric atmospheric fog effect mod for Minecraft 1.21.1 (NeoForge). Low-lying fog fills the air with a smooth, continuous surface that looks identical from any viewing angle — standing on the ground, flying above, or suspended in the fog itself.

## Features

- **Screen-space raymarching** — fog is rendered as a full-screen effect, not box geometry
- **Procedural fog surface** — 4-octave FBM noise creates natural-looking bumpy fog top
- **World-aware** — fog top height varies spatially across the world; animates slowly over time
- **Server-authoritative** — server config syncs to all players on join
- **Configurable** — adjust fog height, density, color, and appearance per world
- **No vanilla conflicts** — replaces vanilla fog entirely; works with other mods

## Installation

1. Download the latest `.jar` from [Releases](../../releases)
2. Place in your `mods/` folder
3. Start Minecraft; the mod initializes with sensible defaults
4. (Optional) Edit `saves/<world>/serverconfig/flatfog-server.toml` to customize fog parameters

## Configuration

Edit `saves/<world>/serverconfig/flatfog-server.toml`:

```toml
[fog]
fog_top_y         = 100.0      # World Y level where fog fills up to
fog_bottom_y      = -64.0      # Fog floor (bedrock level)
fog_density       = 1.5        # Extinction multiplier (0.0-5.0; higher = thicker)
height_variation  = 10.0       # Amplitude of fog-top bumps in blocks
height_scale      = 0.003      # Spatial frequency of bumps (smaller = larger features)

[fog_color]
r = 0.82                        # Red channel (0-1)
g = 0.88                        # Green channel (0-1)
b = 0.96                        # Blue channel (0-1)
a = 0.92                        # Opacity (0-1)
```

**Tuning tips:**
- Reduce `height_variation` for a flatter, more uniform fog
- Increase `fog_density` for thicker, more opaque fog
- Adjust `height_scale` to make fog pockets larger or smaller
- Raise `fog_top_y` to create a higher fog layer; lower for ground-hugging fog

## How It Works

The fog is rendered at `RenderLevelStageEvent.Stage.AFTER_WEATHER` via a screen-space quad. Each pixel's fragment shader:

1. Reconstructs a world-space ray from the camera through that pixel
2. Finds where the ray enters/exits the fog band (Y from `fog_bottom_y` to `fog_top_y`)
3. Samples the depth buffer to clamp the ray at solid geometry
4. Steps through the fog band, evaluating an FBM function at each step to find the local fog-top height
5. Accumulates fog extinction using Beer-Lambert law
6. Blends the fog color over the scene

The noise function is entirely procedural (no texture), ensuring the fog is infinitely detailed and world-stable.

## Known Issues & Architecture Notes

See [SESSION_NOTES.txt](SESSION_NOTES.txt) for:
- Current known issues (depth clamping, temporal aliasing)
- Session history of fixes attempted
- Technical design decisions and tradeoffs
- Diagnostic suggestions for future troubleshooting

## Building

```bash
./gradlew build
```

Output jar: `build/libs/flatfog-1.21.1-0.1.0.jar`

## Requirements

- Minecraft 1.21.1
- NeoForge 1.21.1 (7.0.168+)
- Java 21+

## License

MIT License — see LICENSE file.

## Credits

Developed as a replacement for the earlier "Mist" mod, switching from textured fog layers to procedural screen-space volumetric rendering to eliminate visual seams and enable future per-region density variation.
