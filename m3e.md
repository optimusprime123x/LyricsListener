---
name: material-3-expressive-design
description: Comprehensive guide and rule set for implementing Material 3 Expressive design, focusing on emotion-driven UX, motion physics, variable typography, and shape tension. Enhanced with web and X research, specifically tailored for Flutter apps.
version: 1.1.0
---

# Material 3 Expressive Design Skill

This document provides a comprehensive context, rules, and code patterns required to implement **Material 3 Expressive**. Unlike standard Material 3 (which prioritizes utility and readability), Expressive design prioritizes **emotion, boldness, and distinctiveness** through intentional manipulation of shape, motion, and typography. This enhanced version incorporates insights from official Google Material Design guidelines (m3.material.io), research on expressive UX, and community implementations in Flutter, including GitHub issues and pub.dev packages.

Material 3 Expressive is an evolution of the Material 3 design system, introducing new features, updated components, and design tactics for emotionally impactful UX. It builds on Material You's personalization, adding vibrant colors, intuitive motion, adaptive components, and flexible typography. Research shows expressive designs are preferred by users of all ages, improve usability (e.g., spotting UI elements 4x faster), and boost engagement.

---

## Core Philosophy

**“Design that feels right.”**

Expressive design shifts from purely functional UI to emotional UI. It introduces deliberate variance to create **Hero Moments**—interactions that feel delightful, alive, and memorable. Backed by Google's UX research, it emphasizes emotional connection through color, shape, size, motion, and containment, making products more usable and engaging.

Key Benefits from Research:
- Well-applied expressive design is strongly preferred over non-expressive.
- Users spot key UI elements faster in expressive screens.
- Increases product switching likelihood due to emotional appeal.

---

## 1. The Four Pillars of Expressiveness

### A. Motion Physics (The “Spring”)

- **Concept:** Replace standard easing curves (`ease-in`, `ease-out`) with **spring physics** for more natural, fluid interactions.
- **Standard vs Expressive:**
  - **Standard:** Direct, efficient. Ideal for scrolling, lists, and toggles (e.g., cubic easing).
  - **Expressive:** Elastic, playful, includes **overshoot** and bounce. Reserved for hero interactions like button presses or transitions.
- **Token Model:**  
  `duration + easing` → `stiffness + damping`. New simplified spring-based system makes interactions feel alive.
- **Guidelines:** Use for <20% of interactions to avoid overwhelming users. Apply to loading indicators, FAB expansions, or shape morphing.

### B. Expressive Typography

- **Concept:** Use **variable fonts** (e.g., Roboto Flex) to create nuanced styles between predefined ones, adding emotional emphasis.
- **Rule:** Create emphasized variants of standard tokens.
  - Standard: `HeadlineLarge`
  - Expressive: `HeadlineLargeEmphasized` (e.g., bolder weight, wider width via font-variation-settings).
- **Usage:** Short, high-impact text only (titles, metrics, editorial moments).  
  **Never** for body copy to maintain readability.
- **Expanded Type Scale:** Includes new emphasized styles for display, headline, title, label, and body roles. Use variable axes for emotional states (e.g., 'wght' 800, 'wdth' 110).

### C. Shape & Tension

- **Concept:** Visual tension via mixing **fully rounded** and **sharp** corners. Expanded library with 35 new shapes for decorative detail and morphing.
- **Expressive Shape Rule:**  
  Primary containers default to **extra-large or full rounding** (28dp+ or 'full' at 50% of size). Use asymmetry for tension.
- **Morphing:** Shapes transition across states (e.g., rectangle → circle, or spiky star for playful elements).
- **Principles:** Variety of shapes communicates tone; use for avatars, image crops, progress indicators.

### D. Layout & Containment

- **Concept:** Group content into bold, high-contrast containers to guide attention.
- **Hero Areas:** Allocate significant space to a single expressive action or visual.
- **Layout Strategy:** Split expressive and functional zones. Use contrasted shades for important elements, common regions for grouping.
- **Tactics:** 
  1. Variety of shapes for visual rhythm.
  2. Rich, nuanced colors from extended palettes.
  3. Typography to guide attention.
  4. Containment for emphasis (e.g., rounded containers separate groups).

---

## 2. Implementation Rules for Agents

### Do’s and Don’ts

**DO**
- Use **spring-based motion** for expressive transitions.
- Use distinct container colors to separate hero and utility content.
- Use **tonal, colored shadows** derived from the primary palette.
- Apply expressive features to boost usability and emotional impact.
- Ensure accessibility: Semantic structure, color contrast, logical navigation.

**DO NOT**
- Apply expressive motion universally (target <20% of interactions).
- Use gray or neutral shadows.
- Animate high-frequency UI elements.
- Overuse shapes without purpose—avoid cognitive overload.
- Ignore platform specifics; adapt for Wear OS (e.g., circular form factor).

### Expressive Components Overview
15 new/updated components with more sizes, shapes, functionality:
- Button groups: Built-in shape morph, adaptive to window sizes.
- FAB menu: Shows multiple actions with fluid expansion.
- Loading indicator: Captures attention with spring motion.
- Split button: Packs actions into smaller space.
- Toolbars: Floating or docked, with shape options.
- Menus: Expressive variants.
- Lists: Baseline vs. expressive.
- Others: Avatars, carousels, switches (morphing thumbs/icons).

---

## 3. Platform-Specific Guidance

### 📱 Jetpack Compose (Android)
- **Material3:** `androidx.compose.material3:material3:1.3.0+`
- Use built-in support for M3 Expressive (e.g., spring animations, shape tokens).

```kotlin
val expressiveSpring = spring(
    dampingRatio = 0.8f,
    stiffness = 300f
)
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
```

### 💙 Flutter
- **ThemeData:** Enable with `useMaterial3: true` in `MaterialApp`.
- **Current Status:** Official support is not yet active (GitHub issue #168813 tracks progress; community-driven for now). Use custim implementation (preferred) or community packages for expressive features:
  - `flutter_m3shapes` for 35+ shapes and morphing.
  - `tofu_expressive` for full theme with dynamic colors.
  - Component packages: `icon_button_m3e`, `split_button_m3e`, `toolbar_m3e`, `navigation_bar_m3e`, `button_m3e`, `connected_button_group`.
- **Motion:** Use `Curves.elasticOut` or custom `SpringSimulation` for physics. Experiment with `AnimatedContainer` for magnetic animations.
- **Typography:** Load variable fonts like Roboto Flex; adjust via `FontVariation`.
- **Shapes:** Custom `ShapeBorder` or use `flutter_m3shapes` for pixelTriangle, etc.
- **Guidelines for Wear OS:** Center round displays; use scrolling animations tracing the curve.
- **Implementation Tips:** Avoid native conflicts; extend `ThemeData` with `ThemeExtension` for custom expressive layers. Use `InheritedWidget` for wrapping.

```dart
// Expressive Button Morph Example
AnimatedContainer(
  duration: const Duration(milliseconds: 600),
  curve: Curves.elasticOut,
  width: isHovered ? 200 : 60,
  height: 60,
  decoration: BoxDecoration(
    color: Theme.of(context).colorScheme.primary,
    borderRadius: BorderRadius.circular(isHovered ? 16 : 30),
  ),
  child: Icon(
    Icons.add,
    color: Theme.of(context).colorScheme.onPrimary,
  ),
);

// Using flutter_m3shapes package
M3Container.pixelTriangle(
  color: Colors.deepOrange,
  child: Text('Expressive Shape'),
);
```

Prefer `FloatingActionButton.large` for primary actions. For adaptive layouts, use `MediaQuery` to handle window dimensions.

### 🌐 Web (CSS / SCSS)
- Load variable fonts.
```css
@font-face {
  font-family: 'Roboto Flex';
  src: url('RobotoFlex-VariableFont.ttf');
}

.expressive-headline {
  font-variation-settings: 'wght' 800, 'wdth' 110;
}
```
- Use linear() easing or libraries like Framer Motion for springs.

---

## 4. Component Reference Guide

| Component          | Standard M3              | Expressive M3                                      |
|--------------------|--------------------------|----------------------------------------------------|
| Container          | Rounded (12dp)           | Fully rounded (28dp+) or asymmetric                |
| Motion             | Cubic easing             | Spring physics                                     |
| FAB                | Default                  | Large / Extended with animated icon / Menu         |
| Typography         | Static weights           | Variable font axes, emphasized styles              |
| Palette            | Surface 1–5              | Surface Container High / Highest, vibrant palettes |
| Switch             | Simple toggle            | Morphing thumb or icon                             |
| Button Groups      | N/A                      | Adaptive size/shape/padding, morphing              |
| Loading Indicator  | Basic                    | Attention-capturing with spring                    |
| Menus              | Baseline                 | Expressive variants                                |
| Toolbars           | Standard                 | Floating/docked, shape options                     |

---

## 5. Code Scenarios

### A. Hero Card (Flutter)
```dart
Card(
  shape: RoundedCornerShape(
    topLeft: Radius.circular(4),
    topRight: Radius.circular(32),
    bottomRight: Radius.circular(32),
    bottomLeft: Radius.circular(32),
  ),
  color: Theme.of(context).colorScheme.primaryContainer,
  child: SizedBox(
    width: 300,
    height: 200,
    child: // Hero content
  ),
);
```

### B. Expressive Loading Indicator (Flutter with Custom Animation)
```dart
AnimatedContainer(
  duration: Duration(milliseconds: 800),
  curve: Curves.elasticOut,
  child: CircularProgressIndicator(
    valueColor: AlwaysStoppedAnimation<Color>(Theme.of(context).colorScheme.primary),
  ),
);
```

### C. Button Group (Using button_m3e package)
```dart
ConnectedButtonGroup(
  children: [
    ButtonM3e(text: 'Action 1'),
    ButtonM3e(text: 'Action 2'),
  ],
);
```

### D. Toolbar (Using toolbar_m3e package)
```dart
ToolbarM3E(
  title: 'Expressive Toolbar',
  actions: [IconButtonM3E(icon: Icons.more_vert)],
);
```

---

## 6. Verification Checklist
- [ ] Spring or overshoot motion used where expressive.
- [ ] Primary containers use large corner radii (>24dp) or full.
- [ ] Hero text uses expressive typography with variable fonts.
- [ ] Tonal surfaces used instead of pure black/white.
- [ ] Components adapt to window sizes (responsive in Flutter).
- [ ] Accessibility: Contrast ratios met, semantic structure.
- [ ] <20% interactions are expressive to avoid overload.
- [ ] Tested on multiple platforms (mobile, web, Wear OS).
- [ ] Incorporated community packages for Flutter gaps.

```
