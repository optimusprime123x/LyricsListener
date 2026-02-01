---
name: material-3-expressive-design
description: Comprehensive guide and rule set for implementing Material 3 Expressive design, focusing on emotion-driven UX, motion physics, variable typography, and shape tension.
version: 1.0.0
---

# Material 3 Expressive Design Skill

This document provides the comprehensive context, rules, and code patterns required to implement **Material 3 Expressive**. Unlike standard Material 3 (which prioritizes utility and readability), Expressive design prioritizes **emotion, boldness, and distinctiveness** through intentional manipulation of shape, motion, and typography.

---

## Core Philosophy

**“Design that feels right.”**

Expressive design shifts from purely functional UI to emotional UI. It introduces deliberate variance to create **Hero Moments**—interactions that feel delightful, alive, and memorable.

---

## 1. The Four Pillars of Expressiveness

### A. Motion Physics (The “Spring”)

- **Concept:** Replace standard easing curves (`ease-in`, `ease-out`) with **spring physics**.
- **Standard vs Expressive**
  - **Standard:** Direct, efficient. Ideal for scrolling, lists, and toggles.
  - **Expressive:** Elastic, playful, includes **overshoot**. Reserved for hero interactions.
- **Token Model:**  
  `duration + easing` → `stiffness + damping`

---

### B. Expressive Typography

- **Concept:** Use **variable fonts** (e.g., Roboto Flex) to land between predefined styles.
- **Rule:** Create emphasized variants of standard tokens.
  - Standard: `HeadlineLarge`
  - Expressive: `HeadlineLargeEmphasized`
- **Usage:** Short, high-impact text only (titles, metrics).  
  **Never** body copy.

---

### C. Shape & Tension

- **Concept:** Visual tension is created by mixing **fully rounded** and **sharp** corners.
- **Expressive Shape Rule:**  
  Primary containers default to **extra-large or full rounding** (28dp+).
- **Morphing:** Shapes should transition across states (e.g., rectangle → circle).

---

### D. Layout & Containment

- **Concept:** Group content into bold, high-contrast containers.
- **Hero Areas:** Allocate significant space to a single expressive action or visual.
- **Layout Strategy:** Split expressive and functional zones.

---

## 2. Implementation Rules for Agents

### Do’s and Don’ts

**DO**
- Use **spring-based motion** for expressive transitions.
- Use distinct container colors to separate hero and utility content.
- Use **tonal, colored shadows** derived from the primary palette.

**DO NOT**
- Apply expressive motion universally (target <20% of interactions).
- Use gray or neutral shadows.
- Animate high-frequency UI elements.

---

## 3. Platform-Specific Guidance

### 📱 Jetpack Compose (Android)

- **Material3:** `androidx.compose.material3:material3:1.3.0+`

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
💙 Flutter
ThemeData(
  useMaterial3: true,
)
const Curve expressiveCurve = Curves.elasticOut;
Use AnimatedContainer or physics-based animations for shape morphing.

Prefer FloatingActionButton.large for primary actions.

🌐 Web (CSS / SCSS)
@font-face {
  font-family: 'Roboto Flex';
  src: url('RobotoFlex-VariableFont.ttf');
}

.expressive-headline {
  font-variation-settings: 'wght' 800, 'wdth' 110;
}
Use linear() easing or motion libraries (e.g., Framer Motion) for spring behavior.

4. Component Reference Guide
Component	Standard M3	Expressive M3
Container	Rounded (12dp)	Fully rounded (28dp+) or asymmetric
Motion	Cubic easing	Spring physics
FAB	Default	Large / Extended with animated icon
Typography	Static weights	Variable font axes
Palette	Surface 1–5	Surface Container High / Highest
Switch	Simple toggle	Morphing thumb or icon
5. Code Scenarios
A. Hero Card (Jetpack Compose)
Card(
    shape = RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 32.dp,
        bottomEnd = 32.dp,
        bottomStart = 32.dp
    ),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer
    ),
    modifier = Modifier.size(width = 300.dp, height = 200.dp)
) {
    // Hero content
}
B. Expressive Button Morph (Flutter)
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
)
6. Verification Checklist
 Spring or overshoot motion used where expressive

 Primary containers use large corner radii (>24dp)

 Hero text uses expressive typography

 Tonal surfaces used instead of pure black or white
