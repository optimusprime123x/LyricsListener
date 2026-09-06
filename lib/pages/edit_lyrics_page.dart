import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const MethodChannel _platformChannel = MethodChannel(
  'dev.optimus.lyricslistener/permissions',
);

/// Page for editing a cached lyrics entry.
class EditLyricsPage extends StatefulWidget {
  const EditLyricsPage({
    super.key,
    required this.cacheKey,
    required this.title,
    required this.artist,
    required this.type,
  });

  final String cacheKey;
  final String title;
  final String artist;
  final String type;

  @override
  State<EditLyricsPage> createState() => _EditLyricsPageState();
}

class _EditLyricsPageState extends State<EditLyricsPage> {
  bool _isLoading = true;
  bool _isSaving = false;
  String? _errorMessage;
  Map<String, dynamic>? _lyricsData;

  // For plain lyrics
  late TextEditingController _plainLyricsController;

  // For synced lyrics
  List<_SyncedLineData> _syncedLines = [];

  @override
  void initState() {
    super.initState();
    _plainLyricsController = TextEditingController();
    _loadContent();
  }

  @override
  void dispose() {
    _plainLyricsController.dispose();
    for (final line in _syncedLines) {
      line.textController.dispose();
      line.timestampController.dispose();
    }
    super.dispose();
  }

  Future<void> _loadContent() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final Map<dynamic, dynamic>? result =
          await _platformChannel.invokeMethod<Map<dynamic, dynamic>>(
        'getCachedLyricsContent',
        {'cacheKey': widget.cacheKey},
      );

      if (result != null && mounted) {
        final data = Map<String, dynamic>.from(result);
        setState(() {
          _lyricsData = data;

          if (widget.type == 'plain') {
            _plainLyricsController.text = data['lyrics'] as String? ?? '';
          } else if (widget.type == 'synced') {
            final lines = data['lines'] as List<dynamic>? ?? [];
            _syncedLines = lines.map((line) {
              final lineMap = Map<String, dynamic>.from(line as Map);
              final timestamp = (lineMap['timestamp'] as num?)?.toInt() ?? 0;
              final text = lineMap['text'] as String? ?? '';
              return _SyncedLineData(
                timestamp: timestamp,
                text: text,
                timestampController: TextEditingController(
                  text: _formatTimestamp(timestamp),
                ),
                textController: TextEditingController(text: text),
              );
            }).toList();
          }

          _isLoading = false;
        });
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() {
          _errorMessage =
              'Failed to load lyrics: ${e.message ?? "Unknown error"}';
          _isLoading = false;
        });
      }
    }
  }

  String _formatTimestamp(int ms) {
    final duration = Duration(milliseconds: ms);
    final minutes = duration.inMinutes.toString().padLeft(2, '0');
    final seconds = (duration.inSeconds % 60).toString().padLeft(2, '0');
    final millis = ((ms % 1000) ~/ 10).toString().padLeft(2, '0');
    return '$minutes:$seconds.$millis';
  }

  int _parseTimestamp(String text) {
    try {
      final parts = text.split(':');
      if (parts.length == 2) {
        final minutes = int.parse(parts[0]);
        final secParts = parts[1].split('.');
        final seconds = int.parse(secParts[0]);
        final millis = secParts.length > 1
            ? int.parse(secParts[1].padRight(3, '0').substring(0, 3))
            : 0;
        return (minutes * 60 + seconds) * 1000 + millis;
      }
    } catch (_) {}
    return 0;
  }

  Future<void> _save() async {
    setState(() => _isSaving = true);

    try {
      Map<String, dynamic> updatedData;

      if (widget.type == 'plain') {
        updatedData = {'lyrics': _plainLyricsController.text};
      } else {
        final lines = _syncedLines.map((line) {
          return {
            'timestamp': _parseTimestamp(line.timestampController.text),
            'text': line.textController.text,
          };
        }).toList();
        updatedData = {'lines': lines};
      }

      final bool? success = await _platformChannel.invokeMethod<bool>(
        'updateCachedLyrics',
        {'cacheKey': widget.cacheKey, 'data': updatedData},
      );

      if (mounted) {
        if (success == true) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Lyrics saved')),
          );
          Navigator.of(context).pop();
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Failed to save lyrics')),
          );
        }
      }
    } on PlatformException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error: ${e.message ?? "Unknown error"}')),
        );
      }
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Edit lyrics',
              style: textTheme.titleMedium,
            ),
            Text(
              '${widget.title} — ${widget.artist}',
              style: textTheme.bodySmall?.copyWith(
                color: colorScheme.onSurfaceVariant,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
        actions: [
          if (!_isLoading && _errorMessage == null)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: FilledButton.icon(
                onPressed: _isSaving ? null : _save,
                icon: _isSaving
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.save_rounded),
                label: const Text('Save'),
              ),
            ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _errorMessage != null
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.error_outline_rounded,
                          size: 48,
                          color: colorScheme.error,
                        ),
                        const SizedBox(height: 16),
                        Text(
                          _errorMessage!,
                          textAlign: TextAlign.center,
                          style: textTheme.bodyLarge,
                        ),
                        const SizedBox(height: 16),
                        FilledButton.icon(
                          onPressed: _loadContent,
                          icon: const Icon(Icons.refresh_rounded),
                          label: const Text('Retry'),
                        ),
                      ],
                    ),
                  ),
                )
              : widget.type == 'plain'
                  ? _buildPlainEditor(context)
                  : _buildSyncedEditor(context),
    );
  }

  Widget _buildPlainEditor(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return Padding(
      padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + MediaQuery.paddingOf(context).bottom),
      child: Container(
        decoration: BoxDecoration(
          color: colorScheme.surfaceContainerHigh,
          borderRadius: BorderRadius.circular(28),
        ),
        padding: const EdgeInsets.all(4),
        child: TextField(
          controller: _plainLyricsController,
          maxLines: null,
          expands: true,
          textAlignVertical: TextAlignVertical.top,
          decoration: InputDecoration(
            hintText: 'Enter lyrics...',
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(24),
              borderSide: BorderSide.none,
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(24),
              borderSide: BorderSide.none,
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(24),
              borderSide: BorderSide.none,
            ),
            filled: true,
            fillColor: colorScheme.surfaceContainerHigh,
            contentPadding: const EdgeInsets.all(20),
          ),
        ),
      ),
    );
  }

  Widget _buildSyncedEditor(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Section header
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: colorScheme.secondaryContainer,
                  borderRadius: BorderRadius.circular(28),
                ),
                child: Text(
                  '${_syncedLines.length} lines',
                  style: textTheme.labelMedium?.copyWith(
                    color: colorScheme.onSecondaryContainer,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
        ),
        Expanded(
          child: ListView.builder(
            padding: EdgeInsets.fromLTRB(16, 4, 16, 16 + MediaQuery.paddingOf(context).bottom),
            itemCount: _syncedLines.length,
            itemBuilder: (context, index) {
              final line = _syncedLines[index];
              return Card(
                elevation: 0,
                color: colorScheme.surfaceContainerHigh,
                margin: const EdgeInsets.symmetric(vertical: 3),
                child: Padding(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  child: Row(
                    children: [
                      // Line number
                      SizedBox(
                        width: 28,
                        child: Text(
                          '${index + 1}',
                          style: textTheme.labelSmall?.copyWith(
                            color: colorScheme.onSurfaceVariant,
                          ),
                          textAlign: TextAlign.center,
                        ),
                      ),
                      const SizedBox(width: 8),
                      // Timestamp field
                      SizedBox(
                        width: 90,
                        child: TextField(
                          controller: line.timestampController,
                          style: textTheme.bodySmall?.copyWith(
                            fontFamily: 'monospace',
                            fontWeight: FontWeight.w600,
                          ),
                          decoration: InputDecoration(
                            isDense: true,
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 10,
                            ),
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(14),
                            ),
                            enabledBorder: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(14),
                              borderSide: BorderSide(
                                color: colorScheme.outlineVariant,
                              ),
                            ),
                            focusedBorder: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(14),
                              borderSide: BorderSide(
                                color: colorScheme.primary,
                                width: 2,
                              ),
                            ),
                            filled: true,
                            fillColor: colorScheme.surfaceContainerHighest,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      // Text field
                      Expanded(
                        child: TextField(
                          controller: line.textController,
                          style: textTheme.bodyMedium,
                          decoration: InputDecoration(
                            isDense: true,
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 10,
                            ),
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(14),
                            ),
                            enabledBorder: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(14),
                              borderSide: BorderSide(
                                color: colorScheme.outlineVariant,
                              ),
                            ),
                            focusedBorder: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(14),
                              borderSide: BorderSide(
                                color: colorScheme.primary,
                                width: 2,
                              ),
                            ),
                            filled: true,
                            fillColor: colorScheme.surfaceContainerHighest,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _SyncedLineData {
  _SyncedLineData({
    required this.timestamp,
    required this.text,
    required this.timestampController,
    required this.textController,
  });

  final int timestamp;
  final String text;
  final TextEditingController timestampController;
  final TextEditingController textController;
}
