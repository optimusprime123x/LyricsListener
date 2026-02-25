import 'package:flutter/material.dart';

/// Material 3 Expressive-inspired page transitions:
/// subtle shared-axis motion with spring-like overshoot on entry.
const PageTransitionsTheme expressivePageTransitionsTheme =
    PageTransitionsTheme(
      builders: <TargetPlatform, PageTransitionsBuilder>{
        TargetPlatform.android: _ExpressivePageTransitionsBuilder(),
        TargetPlatform.fuchsia: _ExpressivePageTransitionsBuilder(),
        TargetPlatform.iOS: _ExpressivePageTransitionsBuilder(),
        TargetPlatform.linux: _ExpressivePageTransitionsBuilder(),
        TargetPlatform.macOS: _ExpressivePageTransitionsBuilder(),
        TargetPlatform.windows: _ExpressivePageTransitionsBuilder(),
      },
    );

class _ExpressivePageTransitionsBuilder extends PageTransitionsBuilder {
  const _ExpressivePageTransitionsBuilder();

  @override
  Duration get transitionDuration => const Duration(milliseconds: 420);

  @override
  Duration get reverseTransitionDuration => const Duration(milliseconds: 340);

  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    if (route.settings.name == null && route.fullscreenDialog) {
      return child;
    }

    final primaryOpacity = CurvedAnimation(
      parent: animation,
      curve: const Interval(0.0, 0.85, curve: Curves.easeOutCubic),
      reverseCurve: Curves.easeOutCubic,
    );
    final primarySlide =
        Tween<Offset>(begin: const Offset(0.055, 0), end: Offset.zero).animate(
          CurvedAnimation(
            parent: animation,
            curve: Curves.easeOutBack,
            reverseCurve: Curves.easeInCubic,
          ),
        );
    final primaryScale = Tween<double>(begin: 0.985, end: 1.0).animate(
      CurvedAnimation(
        parent: animation,
        curve: Curves.easeOutBack,
        reverseCurve: Curves.easeInCubic,
      ),
    );

    final secondaryOpacity = Tween<double>(begin: 1.0, end: 0.94).animate(
      CurvedAnimation(
        parent: secondaryAnimation,
        curve: Curves.easeOutCubic,
        reverseCurve: Curves.easeOutCubic,
      ),
    );
    final secondarySlide =
        Tween<Offset>(begin: Offset.zero, end: const Offset(-0.02, 0)).animate(
          CurvedAnimation(
            parent: secondaryAnimation,
            curve: Curves.easeOutCubic,
            reverseCurve: Curves.easeOutCubic,
          ),
        );
    final secondaryScale = Tween<double>(begin: 1.0, end: 0.985).animate(
      CurvedAnimation(
        parent: secondaryAnimation,
        curve: Curves.easeOutCubic,
        reverseCurve: Curves.easeOutCubic,
      ),
    );

    return FadeTransition(
      opacity: secondaryOpacity,
      child: SlideTransition(
        position: secondarySlide,
        child: ScaleTransition(
          scale: secondaryScale,
          child: FadeTransition(
            opacity: primaryOpacity,
            child: SlideTransition(
              position: primarySlide,
              child: ScaleTransition(scale: primaryScale, child: child),
            ),
          ),
        ),
      ),
    );
  }
}
