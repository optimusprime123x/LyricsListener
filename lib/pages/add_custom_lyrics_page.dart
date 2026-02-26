import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const MethodChannel _platformChannel = MethodChannel(
  'dev.optimus.lyricslistener/permissions',
);

const Curve _expressiveSpringCurve = Curves.elasticOut;

class AddCustomLyricsPage extends StatefulWidget {
  const AddCustomLyricsPage({super.key});

  @override
  State<AddCustomLyricsPage> createState() => _AddCustomLyricsPageState();
}

class _AddCustomLyricsPageState extends State<AddCustomLyricsPage> {
  final _titleController = TextEditingController();
  final _artistController = TextEditingController();
  final _lrcController = TextEditingController();

  bool _isSaving = false;
  bool _submitted = false;
  String? _nativeLrcError;

  @override
  void initState() {
    super.initState();
    _lrcController.addListener(_onLrcChanged);
  }

  @override
  void dispose() {
    _titleController.dispose();
    _artistController.dispose();
    _lrcController
      ..removeListener(_onLrcChanged)
      ..dispose();
    super.dispose();
  }

  void _onLrcChanged() {
    if (_nativeLrcError != null && mounted) {
      setState(() => _nativeLrcError = null);
    }
  }

  String? _basicTitleError() {
    if (!_submitted) return null;
    if (_titleController.text.trim().isEmpty) return 'Title is required';
    return null;
  }

  String? _basicArtistError() {
    if (!_submitted) return null;
    if (_artistController.text.trim().isEmpty) return 'Artist is required';
    return null;
  }

  String? _basicLrcError() {
    if (_nativeLrcError != null) return _nativeLrcError;
    if (!_submitted) return null;
    final lrc = _lrcController.text.trim();
    if (lrc.isEmpty) return 'LRC is required';
    final hasTimestamp = RegExp(r'\[\d{2,}:\d{2}[.:]\d{2,3}\]').hasMatch(lrc);
    if (!hasTimestamp) {
      return 'Add at least one timestamped line like [00:12.34] Hello';
    }
    return null;
  }

  int get _detectedTimestampCount => RegExp(
    r'\[\d{2,}:\d{2}[.:]\d{2,3}\]',
  ).allMatches(_lrcController.text).length;

  bool _validateBasicFields() {
    setState(() {
      _submitted = true;
      _nativeLrcError = null;
    });
    return _basicTitleError() == null &&
        _basicArtistError() == null &&
        _basicLrcError() == null;
  }

  Future<void> _save({bool overwriteExisting = false}) async {
    if (!_validateBasicFields()) return;

    setState(() => _isSaving = true);

    try {
      final rawResult = await _platformChannel
          .invokeMethod<Map<dynamic, dynamic>>('addCustomLyrics', {
            'title': _titleController.text.trim(),
            'artist': _artistController.text.trim(),
            'lrc': _lrcController.text,
            'overwriteExisting': overwriteExisting,
          });

      final result = Map<String, dynamic>.from(rawResult ?? const {});
      final status = result['status'] as String? ?? '';

      if (!mounted) return;

      if (status == 'conflict') {
        final confirmed = await _showOverwriteDialog(result);
        if (confirmed == true && mounted) {
          setState(() => _isSaving = false);
          await _save(overwriteExisting: true);
          return;
        }
        return;
      }

      if (status == 'saved') {
        final overwrittenCount =
            (result['overwrittenCount'] as num?)?.toInt() ?? 0;
        final lineCount = (result['lineCount'] as num?)?.toInt() ?? 0;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              overwrittenCount > 0
                  ? 'Custom lyrics saved ($lineCount lines). Replaced $overwrittenCount cached entr${overwrittenCount == 1 ? "y" : "ies"}.'
                  : 'Custom lyrics saved ($lineCount lines).',
            ),
          ),
        );
        Navigator.of(context).pop(true);
        return;
      }

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Failed to save custom lyrics')),
      );
    } on PlatformException catch (e) {
      if (!mounted) return;
      if (e.code == 'ERROR_INVALID_LRC') {
        setState(() {
          _submitted = true;
          _nativeLrcError = e.message ?? 'Invalid LRC';
        });
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error: ${e.message ?? "Unknown error"}')),
        );
      }
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  Future<bool?> _showOverwriteDialog(Map<String, dynamic> payload) {
    final matchCount = (payload['matchCount'] as num?)?.toInt() ?? 1;
    final matches = (payload['matches'] as List<dynamic>? ?? const [])
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
    final first = matches.isNotEmpty
        ? matches.first
        : const <String, dynamic>{};
    final existingType = (first['type'] as String?) ?? 'unknown';
    final existingSource = (first['source'] as String?) ?? 'unknown';

    return showDialog<bool>(
      context: context,
      builder: (context) {
        final colorScheme = Theme.of(context).colorScheme;
        final textTheme = Theme.of(context).textTheme;

        return AlertDialog(
          shape: const RoundedRectangleBorder(
            borderRadius: BorderRadius.only(
              topLeft: Radius.circular(14),
              topRight: Radius.circular(28),
              bottomLeft: Radius.circular(28),
              bottomRight: Radius.circular(28),
            ),
          ),
          title: const Text('Exact cache match found'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                matchCount == 1
                    ? 'A cached lyrics entry already exists for this title and artist.'
                    : '$matchCount cached lyrics entries already exist for this exact title and artist.',
                style: textTheme.bodyMedium,
              ),
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: colorScheme.surfaceContainerHigh,
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(
                  'Existing: ${existingType.toUpperCase()} • source: $existingSource',
                  style: textTheme.bodySmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                'Overwrite with your custom LRC? It will be stored as source "custom" with duration 0.',
                style: textTheme.bodySmall?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              style: FilledButton.styleFrom(
                backgroundColor: colorScheme.errorContainer,
                foregroundColor: colorScheme.onErrorContainer,
              ),
              child: const Text('Overwrite'),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Scaffold(
      appBar: AppBar(title: const Text('Add custom lyrics')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 20),
          children: [
            AnimatedContainer(
              duration: const Duration(milliseconds: 500),
              curve: _expressiveSpringCurve,
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                color: colorScheme.primaryContainer,
                borderRadius: const BorderRadius.only(
                  topLeft: Radius.circular(18),
                  topRight: Radius.circular(32),
                  bottomLeft: Radius.circular(32),
                  bottomRight: Radius.circular(28),
                ),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: colorScheme.primary,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: Icon(
                      Icons.lyrics_rounded,
                      color: colorScheme.onPrimary,
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Custom Synced Lyrics',
                          style: textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.w700,
                            color: colorScheme.onPrimaryContainer,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'Paste valid LRC text. The app will parse and validate timestamps before saving.',
                          style: textTheme.bodySmall?.copyWith(
                            color: colorScheme.onPrimaryContainer.withValues(
                              alpha: 0.85,
                            ),
                          ),
                        ),
                        const SizedBox(height: 10),
                        AnimatedSwitcher(
                          duration: const Duration(milliseconds: 250),
                          switchInCurve: _expressiveSpringCurve,
                          switchOutCurve: Curves.easeInOut,
                          child: Container(
                            key: ValueKey(_detectedTimestampCount),
                            padding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 6,
                            ),
                            decoration: BoxDecoration(
                              color: colorScheme.surface.withValues(
                                alpha: 0.65,
                              ),
                              borderRadius: BorderRadius.circular(16),
                            ),
                            child: Text(
                              _detectedTimestampCount == 0
                                  ? 'No timestamps detected yet'
                                  : '$_detectedTimestampCount timestamps detected',
                              style: textTheme.labelMedium?.copyWith(
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            _buildFieldCard(
              context: context,
              label: 'Title',
              helperText:
                  'Please use the exact name used on streaming services',
              errorText: _basicTitleError(),
              child: TextField(
                controller: _titleController,
                textInputAction: TextInputAction.next,
                onChanged: (_) => setState(() {}),
                decoration: _fieldDecoration(
                  context,
                  hintText: 'e.g. Blinding Lights',
                ),
              ),
            ),
            const SizedBox(height: 12),
            _buildFieldCard(
              context: context,
              label: 'Artist',
              helperText:
                  'In case of multiple artists, please add all comma-separated',
              errorText: _basicArtistError(),
              child: TextField(
                controller: _artistController,
                textInputAction: TextInputAction.next,
                onChanged: (_) => setState(() {}),
                decoration: _fieldDecoration(
                  context,
                  hintText: 'e.g. Artist One, Artist Two',
                ),
              ),
            ),
            const SizedBox(height: 12),
            _buildFieldCard(
              context: context,
              label: 'LRC',
              helperText:
                  'Use timestamped lines like [00:12.34] lyric text. Metadata tags like [ar:] are allowed.',
              errorText: _basicLrcError(),
              child: SizedBox(
                height: 280,
                child: TextField(
                  controller: _lrcController,
                  maxLines: null,
                  expands: true,
                  textAlignVertical: TextAlignVertical.top,
                  keyboardType: TextInputType.multiline,
                  onChanged: (_) => setState(() {}),
                  style: textTheme.bodyMedium?.copyWith(
                    fontFamily: 'monospace',
                    height: 1.35,
                  ),
                  decoration: _fieldDecoration(
                    context,
                    hintText:
                        '[00:12.34] First line\n[00:17.90] Second line\n[00:25.20] ...',
                    contentPadding: const EdgeInsets.all(16),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: colorScheme.secondaryContainer,
                borderRadius: const BorderRadius.only(
                  topLeft: Radius.circular(28),
                  topRight: Radius.circular(14),
                  bottomLeft: Radius.circular(14),
                  bottomRight: Radius.circular(28),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Save behavior',
                    style: textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w700,
                      color: colorScheme.onSecondaryContainer,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'On save, the app checks cache for an exact title + artist match. If found, you can confirm overwrite.',
                    style: textTheme.bodySmall?.copyWith(
                      color: colorScheme.onSecondaryContainer.withValues(
                        alpha: 0.88,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: _isSaving ? null : _save,
                icon: _isSaving
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.save_rounded),
                label: Text(_isSaving ? 'Saving...' : 'Save custom lyrics'),
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(28),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFieldCard({
    required BuildContext context,
    required String label,
    required String helperText,
    required Widget child,
    String? errorText,
  }) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return AnimatedContainer(
      duration: const Duration(milliseconds: 450),
      curve: _expressiveSpringCurve,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(28),
        border: Border.all(
          color: errorText == null ? Colors.transparent : colorScheme.error,
          width: errorText == null ? 0 : 1.2,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 4),
          Text(
            helperText,
            style: textTheme.bodySmall?.copyWith(
              color: colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 12),
          child,
          if (errorText != null) ...[
            const SizedBox(height: 10),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  Icons.error_outline_rounded,
                  size: 16,
                  color: colorScheme.error,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    errorText,
                    style: textTheme.bodySmall?.copyWith(
                      color: colorScheme.error,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  InputDecoration _fieldDecoration(
    BuildContext context, {
    required String hintText,
    EdgeInsetsGeometry contentPadding = const EdgeInsets.symmetric(
      horizontal: 14,
      vertical: 12,
    ),
  }) {
    final colorScheme = Theme.of(context).colorScheme;

    return InputDecoration(
      hintText: hintText,
      filled: true,
      fillColor: colorScheme.surfaceContainerHighest,
      contentPadding: contentPadding,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: BorderSide(color: colorScheme.outlineVariant),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: BorderSide(color: colorScheme.primary, width: 2),
      ),
    );
  }
}
