```markdown
---
name: material-3-expressive-design
description: Comprehensive, production-ready guide and rule set for implementing Material 3 Expressive (May 2025 evolution of M3) — emotion-driven UX, spring physics, variable typography, 35-shape library, morphing, vibrant colors, and hero moments. Tailored for Flutter with community + custom implementations (official Expressive support pending).
version: 2.0.0
---

# Material 3 Expressive Design Skill

**Material 3 Expressive** is the official 2025 evolution of Google’s Material 3 design system. It is **not** a new major version (“M4”) — it is an additive layer of features, updated components, styles, and tactics that make interfaces feel emotionally alive while staying fully compatible with baseline M3.

> “Expressive interfaces have an emotional impact, fostering connection by evoking a feeling or mood through visual design and interaction.”

Backed by the most researched update in Material history (46 studies, 18,000+ participants):

- Expressive designs are strongly preferred by users of **all ages**  
- Key UI elements spotted **up to 4× faster**  
- Higher scores on playfulness, energy, creativity, friendliness  
- Users significantly more likely to switch to expressive products  

This guide focuses on **Flutter** implementation (the primary target of the original document) while referencing official Jetpack Compose/Web patterns for completeness.

---

# Core Philosophy

**“Design that feels right — and makes you feel something.”**

M3 Expressive moves from purely functional UI to **emotional UI**. It deliberately introduces controlled variance in shape, motion, color, typography, and containment to create **Hero Moments** — brief, delightful, surprising interactions that turn ordinary screens into memorable experiences.

---

# The Five Foundations of Expressiveness

---

## A. Motion Physics (The Spring System)

Official replacement for cubic easing.

### Motion Schemes (Set Once at Theme Level)

- **Expressive** (recommended default) — overshoots + natural bounce for hero moments  
- **Standard** — clean, functional, minimal bounce (utility flows)  

### Spring Attributes

- `stiffness` (higher = faster snap)  
- `damping` (1.0 = no bounce)  
- `initialVelocity`  

**Spatial springs** (position, size, corner radius) → overshoot allowed  
**Effects springs** (color, opacity) → no overshoot  

Speed tokens (`fast`, `default`, `slow`) adapt to device context.

### Flutter Implementation (Custom — No Official Tokens Yet)

```dart
import 'package:flutter/physics.dart';

final expressiveSpring = SpringSimulation(
  const SpringDescription.withDampingRatio(
    mass: 1,
    stiffness: 300,
    ratio: 0.8, // lower = more playful bounce
  ),
  0.0,
  1.0,
  0.0,
);

AnimatedBuilder(
  animation: controller.drive(
    Tween(begin: 0.0, end: 1.0).chain(
      CurveTween(curve: Curves.linear),
    ),
  ),
  builder: (context, child) {
    return Transform.scale(
      scale: animation.value,
      child: child,
    );
  },
);
````

Use `flutter_animate` + `Spring` or the `tofu_expressive` package for easier theming.

**Guideline:** Limit expressive motion to **<20%** of interactions. Reserve for Hero Moments.

---

## B. Expressive Typography

* 15 baseline styles
* 15 emphasized styles

Emphasized styles use heavier weight, wider tracking, or variable font axes for emotional impact.

### Recommended Variable Fonts

* Roboto Flex
* Google Sans Flex
* Roboto Serif

### Flutter Example

```dart
TextStyle headlineLargeEmphasized = const TextStyle(
  fontFamily: 'RobotoFlex',
  fontVariations: [
    FontVariation('wght', 800),
    FontVariation('wdth', 110),
  ],
);

ThemeData(
  textTheme: Theme.of(context).textTheme.copyWith(
    headlineLarge: headlineLargeEmphasized,
  ),
);
```

**Rule:** Emphasized styles are for short, high-impact text only (titles, metrics, CTAs, editorial moments).
Never use for body copy.

---

## C. Shape & Tension

* 35 new shapes
* Built-in morphing in official Material Shape Library

### Updated Corner Radius Scale

* Large → 20 dp
* Extra-large → 32 dp
* Extra-extra-large → 48 dp
* Fully rounded → `full` (not 50% of size)

Asymmetry + mixing round and sharp corners creates deliberate visual tension.

Shape morphing communicates:

* Press state
* Selection
* Loading
* Environmental feedback

### Flutter (Using `flutter_m3shapes`)

```dart
import 'package:flutter_m3shapes/flutter_m3shapes.dart';

M3Shape.roundedRectangle(
  borderRadius: BorderRadius.circular(32),
);

M3Shape.pixelTriangle();
M3Shape.wavy();
```

Use `AnimatedContainer` + custom `ShapeBorder` for morph transitions.

---

## D. Vibrant & Nuanced Color

Use dynamic color + extended palettes:

* primary
* secondary
* tertiary
* neutral
* error
* surfaceContainerHigh
* surfaceContainerHighest

### Expressive Color Tactics

* Strong contrast between roles for hierarchy
* Colored/tonal shadows instead of gray
* Surface Container High / Highest for hero containers

The `tofu_expressive` package provides ready-made expressive schemes with dynamic color support.

---

## E. Layout & Containment

* Group content into bold, high-contrast containers
* Create expressive zones vs functional zones
* Use generous white space and size contrast

Containment directs attention and strengthens hierarchy.

---

# Expressive Design Tactics (Official)

* Use a variety of shapes (mix round + angular for tension)
* Apply rich, nuanced colors with strong contrast
* Guide attention with emphasized typography
* Contain content for emphasis
* Add fluid, natural motion (springs + morphing)
* Leverage component flexibility
* Combine tactics to create Hero Moments (limit to 1–2 per screen)

---

# Implementation Rules for Flutter Agents

## DO

* Set expressive motion scheme as default
* Use large / extra-large rounding (28 dp+) or `full` for primary containers
* Leverage variable fonts + emphasized styles for hero text
* Use `tofu_expressive` + `flutter_m3shapes` + custom `ThemeExtension`
* Create 1–2 true Hero Moments per major screen

## DO NOT

* Apply expressive motion everywhere
* Use gray shadows
* Animate high-frequency elements (lists, scroll)
* Overuse abstract shapes
* Ship without accessibility testing

---

# Flutter Implementation Status (Feb 2026)

**Official Flutter SDK:** Baseline M3 only (`useMaterial3: true`)
Full Expressive support not yet shipped.

### Recommended Production Stack

* `tofu_expressive` — expressive theme + dynamic color
* `flutter_m3shapes` — 35+ shapes + morphing
* `flutter_animate` or custom `SpringSimulation`
* Custom `ThemeExtension<ExpressiveTheme>`

---

## Example: Expressive Theme Extension

```dart
class ExpressiveTheme extends ThemeExtension<ExpressiveTheme> {
  final SpringDescription spring;
  final TextStyle headlineEmphasized;

  const ExpressiveTheme({
    required this.spring,
    required this.headlineEmphasized,
  });

  @override
  ExpressiveTheme copyWith({
    SpringDescription? spring,
    TextStyle? headlineEmphasized,
  }) {
    return ExpressiveTheme(
      spring: spring ?? this.spring,
      headlineEmphasized:
          headlineEmphasized ?? this.headlineEmphasized,
    );
  }

  @override
  ExpressiveTheme lerp(
    ThemeExtension<ExpressiveTheme>? other,
    double t,
  ) {
    return this;
  }
}

MaterialApp(
  theme: tofuExpressiveTheme(
    colorScheme: dynamicColorScheme,
    useMaterial3: true,
  ).copyWith(
    extensions: [
      ExpressiveTheme(
        spring: const SpringDescription(
          mass: 1,
          stiffness: 300,
          damping: 20,
        ),
        headlineEmphasized: headlineLargeEmphasized,
      ),
    ],
  ),
);
```

---

## Hero Button with Shape Morph + Spring

```dart
AnimatedContainer(
  duration: const Duration(milliseconds: 600),
  curve: Curves.easeOutBack,
  decoration: BoxDecoration(
    color: colorScheme.primary,
    shape: isPressed
        ? BoxShape.circle
        : BoxShape.rectangle,
    borderRadius: isPressed
        ? null
        : BorderRadius.circular(32),
  ),
  child: const Icon(Icons.play_arrow),
);
```

---

# Component Reference (Expressive Updates)

| Component             | Baseline M3       | Expressive M3 Highlights                           |
| --------------------- | ----------------- | -------------------------------------------------- |
| Buttons               | Standard shapes   | Shape morph on press/select, more sizes            |
| FAB                   | Default           | Large/Extended with animated icon + menu expansion |
| Lists                 | Standard          | Segmented style, improved selection                |
| Cards / Containers    | 12–16 dp rounding | 28–48 dp or full + asymmetric + morphing           |
| NavigationBar / Rail  | Fixed             | Adaptive shape, emphasized icons                   |
| Loading Indicators    | Spinner           | Morphing shapes with spring                        |
| Toolbars / AppBars    | Standard          | Floating, shape options                            |
| Switches              | Simple toggle     | Morphing thumb + icon                              |
| Button Groups         | N/A               | Connected, adaptive, morphing                      |
| Menus / Bottom Sheets | Baseline          | Variable width, expressive variants                |

(15 total new/updated components with expanded configuration and emphasis.)

---

# Code Scenarios

## A. Expressive Hero Card (Asymmetric Shape)

```dart
Card(
  shape: const RoundedRectangleBorder(
    borderRadius: BorderRadius.only(
      topLeft: Radius.circular(8),
      topRight: Radius.circular(32),
      bottomLeft: Radius.circular(32),
      bottomRight: Radius.circular(32),
    ),
  ),
  color: colorScheme.surfaceContainerHighest,
  elevation: 6,
  child: // hero content
);
```

---

## B. Spring-Powered FAB Menu

Use `tofu_expressive` + `flutter_animate`
Or custom controller with `SpringSimulation`.

---

## C. Variable-Font Emphasized Headline

```dart
Text(
  'Start Breathing',
  style: Theme.of(context)
      .textTheme
      .headlineLarge!
      .copyWith(
        fontVariations: const [
          FontVariation.weight(800),
          FontVariation.width(110),
        ],
        color: colorScheme.primary,
      ),
);
```

# Verification Checklist

* [ ] Motion scheme set to Expressive (or deliberate Standard)
* [ ] Primary hero containers use ≥28 dp or `full` rounding
* [ ] At least one Hero Moment per major screen (≥3 tactics combined)
* [ ] Emphasized typography used for key text only
* [ ] Tonal/colored surfaces and shadows (no gray)
* [ ] Shape variety + morphing where meaningful
* [ ] <20% of interactions use expressive motion
* [ ] Full accessibility audit passed
* [ ] Tested on phone, tablet, foldable, web
* [ ] Using `tofu_expressive` + `flutter_m3shapes` or equivalent

```

