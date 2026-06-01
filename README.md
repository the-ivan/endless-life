# Endless Life

A calm, persistent Game of Life running on the Nothing Phone (3) Glyph Matrix.

Life begins when you put the phone face-down. It never really ends — just gently fades and begins again.

## Features

- **Endless cycles** — Seamless pause + 1.5s fade into the next life
- **Time-seeded patterns** — Same minute + second = same starting world (quick deaths get different patterns)
- **Natural endings** — Stabilizes or goes extinct on its own
- **8 starting animations** — All tuned to ~1.15s, chosen randomly
- **Glyph Button** — Long press forces a fresh life
- Designed for Always-on / Flip to Glyph use

## Requirements

- Nothing Phone (3)
- The official [Glyph Matrix SDK](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Development-Kit) (`.aar` in `app/libs/`)

## Quick Start

1. Copy `glyph-matrix-sdk-2.0.aar` into `app/libs/`
2. Open in Android Studio and build
3. Install on your Phone (3)
4. In **Glyph Interface → Glyph Toys**, enable **Endless Life**
5. For the full experience: set it as the **Always-on Glyph Toy** under Flip to Glyph

Put the phone face down. Life starts.

## Project Structure

```
app/src/main/java/com/theivan/endlesslife/
├── EndlessLifeService.kt     # Core loop: reveal → simulate → fade → repeat
├── GlyphMatrixService.kt     # Base for Nothing Glyph Toys
├── LifeGameEngine.kt         # 25×25 Conway's Life (full grid)
├── PatternGenerator.kt       # Time-seeded starting states
├── StabilityDetector.kt      # Detects stable patterns
├── GlyphRenderer.kt          # Grid → Glyph Matrix frames
├── StartingAnimation.kt      # 8 reveal styles (~1.15s)
├── EndingAnimation.kt        # Pause + smooth fade
├── EndlessLifeSettings.kt    # User settings + persistence
└── MainActivity.kt           # Settings screen (dev)
```

## Notes

- Built for **Always-on Glyph Toy** use.
- Long-press the Glyph Button to force a new life.
- No artificial mask — the simulation runs on the full 25×25 grid.
- Brightness is currently fixed (easy to make dynamic from system brightness if desired).

## Credits

- Original concept & inspiration: [Yuma-Eimymk2 / glyph-life](https://github.com/Yuma-Eimymk2/glyph-life)
- Nothing Glyph Matrix SDK and community

MIT license.