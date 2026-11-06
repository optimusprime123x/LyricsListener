import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:material_new_shapes/material_new_shapes.dart';

import 'add_custom_lyrics_page.dart';
import 'manage_lyrics_page.dart';

const _sources = [
  (
    key: 'lyrics_source_lrclib_enabled',
    title: 'LRCLib',
    description:
        'Open database of a large number of synced lyrics, occasionally inaccurate but fast.',
  ),
  (
    key: 'lyrics_source_musixmatch_enabled',
    title: 'Musixmatch',
    description:
        'Huge collection of accurately synced lyrics, however this might be slower and unstable.',
  ),
  (
    key: 'lyrics_source_lrcred_enabled',
    title: 'lrc.red',
    description:
        'Community catalogue of synced lyrics, mostly accurate but reliability varies.',
  ),
  (
    key: 'lyrics_source_others_enabled',
    title: 'Others',
    description: 'Tries Apple Music, Netease, Deezer for uncommon songs.',
  ),
];

class LyricsSourcesPage extends StatefulWidget {
  const LyricsSourcesPage({super.key});

  @override
  State<LyricsSourcesPage> createState() => _LyricsSourcesPageState();
}

class _LyricsSourcesPageState extends State<LyricsSourcesPage> {
  SharedPreferences? _preferences;
  final _enabled = {for (final source in _sources) source.key: true};
  final _saving = <String>{};
  bool _loadFailed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final preferences = await SharedPreferences.getInstance();
      if (!mounted) return;
      setState(() {
        _preferences = preferences;
        _loadFailed = false;
        for (final source in _sources) {
          _enabled[source.key] = preferences.getBool(source.key) ?? true;
        }
      });
    } catch (_) {
      if (mounted) setState(() => _loadFailed = true);
    }
  }

  Future<void> _setEnabled(String key, bool value) async {
    final preferences = _preferences;
    if (preferences == null || _saving.contains(key)) return;
    final previous = _enabled[key]!;
    setState(() {
      _enabled[key] = value;
      _saving.add(key);
    });
    try {
      if (!await preferences.setBool(key, value)) {
        throw StateError('Preference was not saved');
      }
    } catch (_) {
      try {
        await preferences.reload();
      } catch (_) {
        // Keep the last confirmed value if storage is still unavailable.
      }
      if (!mounted) return;
      setState(() => _enabled[key] = previous);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Could not save this source. Try again.')),
      );
    } finally {
      if (mounted) setState(() => _saving.remove(key));
    }
  }

  Widget _link(String text, Widget destination) => TextButton.icon(
    onPressed: () => Navigator.of(
      context,
    ).push(MaterialPageRoute<void>(builder: (_) => destination)),
    style: TextButton.styleFrom(
      minimumSize: const Size(48, 48),
      padding: const EdgeInsets.symmetric(horizontal: 12),
    ),
    icon: const Icon(Icons.arrow_forward_rounded, size: 18),
    label: Text(text),
  );

  Widget _step(
    int number,
    String text, {
    String? supportingText,
    Widget? action,
  }) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        children: [
          Container(
            width: 32,
            constraints: const BoxConstraints(minHeight: 32),
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: colors.secondaryContainer,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Text(
              '$number',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: colors.onSecondaryContainer,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(text, style: theme.textTheme.bodyMedium),
                if (supportingText != null) ...[
                  const SizedBox(height: 8),
                  Text(supportingText, style: theme.textTheme.bodyMedium),
                ],
                if (action != null) ...[const SizedBox(height: 4), action],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _sourceCard({
    required String key,
    required String title,
    required String description,
    required IconData icon,
    required Color accent,
    required Color onAccent,
    required BorderRadius corners,
    bool locked = false,
    bool flower = false,
  }) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final selected = locked || _enabled[key]!;
    final iconBox = ColoredBox(
      color: selected ? accent : colors.surfaceContainerHighest,
      child: SizedBox.square(
        dimension: 44,
        child: Icon(
          icon,
          color: selected ? onAccent : colors.onSurfaceVariant,
          size: 24,
        ),
      ),
    );
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: locked
          ? colors.secondaryContainer.withValues(alpha: 0.45)
          : colors.surfaceContainerHigh,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(borderRadius: corners),
      child: MergeSemantics(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SwitchListTile(
              key: ValueKey(key),
              contentPadding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
              secondary: ExcludeSemantics(
                child: flower
                    ? ClipPath(clipper: const _FlowerClipper(), child: iconBox)
                    : ClipRRect(
                        borderRadius: BorderRadius.circular(locked ? 14 : 24),
                        child: iconBox,
                      ),
              ),
              title: Text(
                title,
                style: theme.textTheme.titleMedium?.copyWith(
                  color: colors.onSurface,
                  fontWeight: FontWeight.w700,
                ),
              ),
              subtitle: locked
                  ? Text(
                      'Always on · Tried first',
                      style: theme.textTheme.labelMedium?.copyWith(
                        color: colors.onSurfaceVariant,
                      ),
                    )
                  : null,
              value: selected,
              thumbIcon: WidgetStateProperty.resolveWith(
                (states) => states.contains(WidgetState.selected)
                    ? Icon(
                        locked ? Icons.lock_rounded : Icons.check_rounded,
                        size: 16,
                      )
                    : null,
              ),
              onChanged:
                  !locked && _preferences != null && !_saving.contains(key)
                  ? (value) => _setEnabled(key, value)
                  : null,
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 18),
              child: Text(
                description,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: colors.onSurfaceVariant,
                  height: 1.45,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _guide() {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      clipBehavior: Clip.antiAlias,
      color: colors.surfaceContainerLow,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
      child: ExpansionTile(
        key: const PageStorageKey('lyrics-source-guide'),
        tilePadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
        childrenPadding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
        shape: const Border(),
        collapsedShape: const Border(),
        leading: Icon(Icons.auto_fix_high_rounded, color: colors.tertiary),
        title: Text(
          'What is this page for?',
          style: theme.textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.w700,
          ),
        ),
        subtitle: const Text('Control lyrics sources and fix inaccuracies'),
        children: [
          const Text(
            'Choose which online sources can fetch lyrics. If the lyrics are wrong or out of sync, try another source for the current song before disabling one entirely:',
          ),
          _step(
            1,
            'Long-press the lyrics in any window mode to open the retry options, then choose another enabled source.',
            supportingText:
                'A successful retry replaces the cached lyrics for that song.',
          ),
          _step(
            2,
            'If a source repeatedly gives inaccurate, or no results, turn it off above to stop future requests there.',
            supportingText:
                'Turning off a source keeps its previously cached lyrics.',
          ),
          _step(
            3,
            'To remove an existing result without retrying, find the song in Manage Lyrics and delete it.',
            action: _link('Manage Lyrics', const ManageLyricsPage()),
          ),
          _step(4, 'Profit!'),
          const SizedBox(height: 20),
          const Text(
            'If none of the enabled sources has accurate lyrics for your song, '
            'you can add your own LRC file:',
          ),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: _link('Custom Lyrics', const AddCustomLyricsPage()),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final ready = _preferences != null;
    final enabledCount = _enabled.values.where((enabled) => enabled).length;
    final sectionStyle = theme.textTheme.titleSmall?.copyWith(
      color: colors.primary,
      fontWeight: FontWeight.w700,
    );
    return Scaffold(
      body: SafeArea(
        child: CustomScrollView(
          key: const PageStorageKey('lyrics-sources-scroll'),
          slivers: [
            SliverAppBar.large(
              pinned: true,
              expandedHeight: MediaQuery.textScalerOf(context).scale(48) + 88,
              backgroundColor: colors.surface,
              title: const Text('Lyrics Sources'),
            ),
            SliverToBoxAdapter(
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 680),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Padding(
                          padding: const EdgeInsets.fromLTRB(8, 0, 8, 24),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Choose which online sources fetch your lyrics.',
                                style: theme.textTheme.bodyLarge?.copyWith(
                                  color: colors.onSurface,
                                ),
                              ),
                              const SizedBox(height: 12),
                              Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Icon(
                                    Icons.info_outline_rounded,
                                    size: 18,
                                    color: colors.onSurfaceVariant,
                                  ),
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Text(
                                      'Changes are saved automatically. You can turn off any source that repeatedly returns inaccurate lyrics.',
                                      style: theme.textTheme.bodySmall
                                          ?.copyWith(
                                            color: colors.onSurfaceVariant,
                                            height: 1.4,
                                          ),
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                        if (_loadFailed)
                          ListTile(
                            title: const Text(
                              'Could not load your source preferences.',
                            ),
                            trailing: TextButton(
                              onPressed: _load,
                              child: const Text('Retry'),
                            ),
                          )
                        else if (!ready)
                          const Padding(
                            padding: EdgeInsets.all(16),
                            child: Center(child: CircularProgressIndicator()),
                          ),
                        Padding(
                          padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                          child: Text('On your device', style: sectionStyle),
                        ),
                        _sourceCard(
                          key: 'lyrics_source_cache',
                          title: 'Cache',
                          description:
                              'Lyrics cached locally in your storage. This is always tried first, and cannot be disabled. Custom lyrics are saved here.',
                          icon: Icons.folder_copy_rounded,
                          accent: colors.secondaryContainer,
                          onAccent: colors.onSecondaryContainer,
                          corners: BorderRadius.circular(28),
                          locked: true,
                        ),
                        const SizedBox(height: 24),
                        Padding(
                          padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
                          child: Wrap(
                            alignment: WrapAlignment.spaceBetween,
                            crossAxisAlignment: WrapCrossAlignment.center,
                            spacing: 12,
                            runSpacing: 8,
                            children: [
                              Text('Online sources', style: sectionStyle),
                              Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 10,
                                  vertical: 4,
                                ),
                                decoration: BoxDecoration(
                                  color: colors.tertiaryContainer,
                                  borderRadius: BorderRadius.circular(20),
                                ),
                                child: Text(
                                  '$enabledCount enabled',
                                  style: theme.textTheme.labelMedium?.copyWith(
                                    color: colors.onTertiaryContainer,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                        for (var i = 0; i < _sources.length; i++) ...[
                          if (i > 0) const SizedBox(height: 4),
                          _sourceCard(
                            key: _sources[i].key,
                            title: _sources[i].title,
                            description: _sources[i].description,
                            icon: [
                              Icons.library_music_rounded,
                              Icons.graphic_eq_rounded,
                              Icons.lyrics_rounded,
                              Icons.travel_explore_rounded,
                            ][i],
                            accent: [
                              colors.primaryContainer,
                              colors.tertiaryContainer,
                              colors.secondaryContainer,
                              colors.primaryContainer,
                            ][i],
                            onAccent: [
                              colors.onPrimaryContainer,
                              colors.onTertiaryContainer,
                              colors.onSecondaryContainer,
                              colors.onPrimaryContainer,
                            ][i],
                            corners: BorderRadius.vertical(
                              top: Radius.circular(i == 0 ? 28 : 4),
                              bottom: Radius.circular(
                                i == _sources.length - 1 ? 28 : 4,
                              ),
                            ),
                            flower: i == 0,
                          ),
                        ],
                        Padding(
                          padding: const EdgeInsets.fromLTRB(12, 14, 12, 24),
                          child: Text(
                            enabledCount == 0
                                ? 'Only cached and custom lyrics will be available while all online sources are off.'
                                : 'Changes apply to the next lyrics fetch. Cached lyrics are kept; long-press the lyrics in any window mode to try another source for the current song.',
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: colors.onSurfaceVariant,
                              height: 1.4,
                            ),
                          ),
                        ),
                        _guide(),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FlowerClipper extends CustomClipper<Path> {
  const _FlowerClipper();

  @override
  Path getClip(Size size) {
    final path = MaterialShapes.flower.toPath();
    final bounds = path.getBounds();
    return path
        .shift(-bounds.topLeft)
        .transform(
          Matrix4.diagonal3Values(
            size.width / bounds.width,
            size.height / bounds.height,
            1,
          ).storage,
        );
  }

  @override
  bool shouldReclip(_FlowerClipper oldClipper) => false;
}
