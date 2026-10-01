# SPEC-07: Hachimi Celestial Design System & UI Specification
**Status**: APPROVED & IMPLEMENTED  
**Edition Compatibility**: `hachimi` (Primary), `standard` (Compatible)  
**Dependencies**: Jetpack Compose BOM 2024.09.00, Material 3, AndroidX CameraX 1.4.1

---

## 1. Design Vision & Principles

The Hachimi UI design elevates the document scanner from a drab utilitarian tool to a high-aesthetic, futuristic mobile scanning studio inspired by **HoYoverse / MetaCubeX / Modern Material 3**.

### Core Tenets
1. **Viewfinder Primacy**: The camera viewfinder is 100% edge-to-edge, seamlessly flowing underneath translucent system status bars and navigation gesture areas.
2. **Frosted Glassmorphism (Starlight Scrims)**: Floating controls use dark translucent surfaces with subtle starlight borders (`1.dp` @ `Color.White.copy(0.15f)`), ensuring razor-sharp legibility over both dark and bright document backgrounds.
3. **Tactile & Visual Feedback**:
   - Camera steady state communicated through color transitions (`#FFB300` Amber -> `#00E676` Emerald Green) and breathing indicator pulses.
   - Snapping events accompanied by haptic feedback (`TextHandleMove`) and high-contrast guide lines.
   - Active scanning modes (Burst, Dewarp) illuminated with glowing accent colors (`#00E5FF` Prism Cyan).

---

## 2. Color Palette & Token Definitions

```kotlin
// Celestial Indigo (Primary / Brand)
val CelestialIndigoLight = Color(0xFF4F46E5)
val CelestialIndigoDark = Color(0xFF6366F1)
val CelestialIndigoContainerLight = Color(0xFFE0E7FF)
val CelestialIndigoContainerDark = Color(0xFF312E81)

// Starlight Lilac (Secondary)
val StarlightLilacLight = Color(0xFF7C3AED)
val StarlightLilacDark = Color(0xFF8B5CF6)
val StarlightLilacContainerLight = Color(0xFFEDE9FE)
val StarlightLilacContainerDark = Color(0xFF4C1D95)

// Prism Cyan (Accent 1 - Burst Mode, Focus, Snap Highlight)
val PrismCyan = Color(0xFF00E5FF)
val PrismCyanGlow = Color(0x3300E5FF)
val PrismCyanDark = Color(0xFF0891B2)

// Emerald Steady (Status - Stable Capture Ready)
val SteadyEmerald = Color(0xFF00E676)
val SteadyEmeraldGlow = Color(0x3300E676)
val SteadyAmber = Color(0xFFFFB300)

// Moe Magenta (Accent 2 - Ribbon Bow & Highlights)
val MoeMagenta = Color(0xFFEC4899)

// Obsidian Night (Surfaces & Scrims)
val ObsidianBase = Color(0xFF0A0F1D)
val ObsidianSurface = Color(0xFF0F172A)
val ObsidianCard = Color(0xFF1E293B)
val ObsidianGlass = Color(0xCC0F172A)
```

---

## 3. Component Specifications

### 3.1 Dual-Ring Shutter Button
- **Outer Stability Ring**: 84dp diameter.
  - Idle/Moving: 2.5dp stroke, `#FFFFFF` with 45% opacity.
  - Stable: 4dp stroke, `#00E676` Emerald with animated transition (`tween(250)`).
  - Burst Mode: `#00E5FF` Cyan with pulsing glow ring.
- **Inner Trigger Circle**: 68dp diameter.
  - Solid white or cyan fill.
  - Tactile press effect with spring physics.
  - Center icon: `AutoAwesome` starlight spark when Burst Mode is active.

### 3.2 Floating Status Pill
- **Placement**: Top center, below status bar insets (`WindowInsets.statusBars`).
- **Surface**: Frosted obsidian glass (`RoundedCornerShape(20.dp)`).
- **Contents**:
  - 8dp status dot: Pulsing green dot when steady, amber when moving.
  - Text: `Steady` / `Hold Steady` in 12sp medium weight.

### 3.3 Mode Toggle Capsules (Burst & Curve)
- Filter capsules with glassmorphism backing (`0x44000000`).
- Selected state: Glowing cyan container (`0x3300E5FF`), bold typography, illuminated icon.
- Instant switch without camera freeze.

### 3.4 Review Gallery Grid & Bottom Dock
- **Card**: 2-column vertical grid, 16dp rounded corners, 0.7 aspect ratio (standard document portrait).
- **Page Tag**: Top-left frosted glass pill: `Page #` or `第 # 页`.
- **Delete Icon**: Top-right frosted circular button.
- **Bottom Dock**: Floating capsule dock above navigation bar with "Add More" (`CameraAlt` icon) and "Export" (`PictureAsPdf` or `Share` icon).

---

## 4. Flavor Asset Verification Checklist

- [x] Master 1024×1024 PNG generated and placed in `references/llm_moe/ic_launcher_1024_master.png`.
- [x] Store 512×512 PNG generated and placed in `references/llm_moe/ic_launcher_512.png`.
- [x] Mipmap densities `mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi` placed in `app/src/hachimi/res/`.
- [x] Adaptive icon vector layers configured with safe-zone bounds (80% scaling).
- [x] Gradle tasks `./gradlew assembleHachimiDebug` and `./gradlew assembleStandardDebug` verified passing.
