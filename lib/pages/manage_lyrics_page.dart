import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'edit_lyrics_page.dart';

const MethodChannel _platformChannel = MethodChannel(
  'dev.optimus.lyricslistener/permissions',
);

const Curve _expressiveSpringCurve = Curves.elasticOut;

/// Page for managing cached lyrics - list, edit, delete, and add custom lyrics.
class ManageLyricsPage extends StatefulWidget {
  const ManageLyricsPage({super.key});

  @override
  State<ManageLyricsPage> createState() => _ManageLyricsPageState();
}

class _ManageLyricsPageState extends State<ManageLyricsPage> {
  List<Map<String, dynamic>> _cachedLyrics = [];
  List<Map<String, dynamic>> _filteredLyrics = [];
  bool _isLoading = true;
  String? _errorMessage;
  final TextEditingController _searchController = TextEditingController();
  Timer? _searchDebounce;

  @override
  void initState() {
    super.initState();
    _loadCachedLyrics();
  }

  @override
  void dispose() {
    _searchController.dispose();
    _searchDebounce?.cancel();
    super.dispose();
  }

  void _onSearchChanged(String query) {
    _searchDebounce?.cancel();
    // Rebuild immediately for suffix icon visibility
    setState(() {});
    _searchDebounce = Timer(const Duration(milliseconds: 200), () {
      _applyFilter(query);
    });
  }

  void _applyFilter(String query) {
    if (!mounted) return;
    setState(() {
      if (query.isEmpty) {
        _filteredLyrics = List.from(_cachedLyrics);
      } else {
        final lowerQuery = query.toLowerCase();
        _filteredLyrics = _cachedLyrics.where((entry) {
          final title = (entry['title'] as String? ?? '').toLowerCase();
          final artist = (entry['artist'] as String? ?? '').toLowerCase();
          return title.contains(lowerQuery) || artist.contains(lowerQuery);
        }).toList();
      }
    });
  }

  Future<void> _loadCachedLyrics() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final List<dynamic>? result =
          await _platformChannel.invokeMethod<List<dynamic>>(
        'getCachedLyricsList',
      );

      if (mounted) {
        setState(() {
          _cachedLyrics = (result ?? [])
              .map((e) => Map<String, dynamic>.from(e as Map))
              .toList();
          _cachedLyrics.sort((a, b) {
            final aTime = (a['cachedAt'] as num?)?.toInt() ?? 0;
            final bTime = (b['cachedAt'] as num?)?.toInt() ?? 0;
            return bTime.compareTo(aTime);
          });
          _applyFilter(_searchController.text);
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

  Future<void> _deleteLyrics(String cacheKey, String title) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        final colorScheme = Theme.of(context).colorScheme;
        return AlertDialog(
          title: const Text('Delete lyrics'),
          content: Text('Are you sure you want to delete "$title"?'),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              style: FilledButton.styleFrom(
                backgroundColor: colorScheme.error,
                foregroundColor: colorScheme.onError,
              ),
              child: const Text('Delete'),
            ),
          ],
        );
      },
    );

    if (confirmed != true) return;

    try {
      final bool? success = await _platformChannel.invokeMethod<bool>(
        'deleteCachedLyrics',
        {'cacheKey': cacheKey},
      );

      if (mounted) {
        if (success == true) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Deleted "$title"')),
          );
          _loadCachedLyrics();
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Failed to delete lyrics')),
          );
        }
      }
    } on PlatformException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error: ${e.message ?? "Unknown error"}')),
        );
      }
    }
  }

  void _editLyrics(Map<String, dynamic> entry) {
    Navigator.of(context)
        .push(
      MaterialPageRoute(
        builder: (_) => EditLyricsPage(
          cacheKey: entry['cacheKey'] as String,
          title: entry['title'] as String? ?? '',
          artist: entry['artist'] as String? ?? '',
          type: entry['type'] as String? ?? 'unknown',
        ),
      ),
    )
        .then((_) {
      _loadCachedLyrics();
    });
  }

  String _formatDuration(int durationMs) {
    final duration = Duration(milliseconds: durationMs);
    final minutes = duration.inMinutes;
    final seconds = (duration.inSeconds % 60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  String _formatType(String type) {
    switch (type) {
      case 'synced':
        return 'Synced';
      case 'plain':
        return 'Plain';
      default:
        return type;
    }
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Manage lyrics'),
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _errorMessage != null
              ? _buildErrorState(colorScheme, textTheme)
              : _buildContent(colorScheme, textTheme),
    );
  }

  Widget _buildErrorState(ColorScheme colorScheme, TextTheme textTheme) {
    return Center(
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
              onPressed: _loadCachedLyrics,
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Retry'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildContent(ColorScheme colorScheme, TextTheme textTheme) {
    return Column(
      children: [
        // Hero header with counter and search
        AnimatedContainer(
          duration: const Duration(milliseconds: 500),
          curve: _expressiveSpringCurve,
          margin: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            color: colorScheme.primaryContainer,
            borderRadius: BorderRadius.circular(28),
          ),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: colorScheme.primary,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Icon(
                  Icons.library_music_rounded,
                  color: colorScheme.onPrimary,
                  size: 28,
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${_cachedLyrics.length}',
                      style: textTheme.headlineMedium?.copyWith(
                        color: colorScheme.onPrimaryContainer,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    Text(
                      'stored lyrics',
                      style: textTheme.bodyMedium?.copyWith(
                        color: colorScheme.onPrimaryContainer,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),

        // Search bar
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
          child: TextField(
            controller: _searchController,
            onChanged: _onSearchChanged,
            decoration: InputDecoration(
              hintText: 'Search by title or artist...',
              prefixIcon: const Icon(Icons.search_rounded),
              suffixIcon: _searchController.text.isNotEmpty
                  ? IconButton(
                      icon: const Icon(Icons.clear_rounded),
                      onPressed: () {
                        _searchController.clear();
                        _applyFilter('');
                      },
                    )
                  : null,
            ),
          ),
        ),

        // Filter status
        if (_searchController.text.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                '${_filteredLyrics.length} of ${_cachedLyrics.length} results',
                style: textTheme.labelMedium?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
            ),
          ),

        // Add custom lyrics button
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
          child: SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: () {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Add custom lyrics — coming soon!'),
                  ),
                );
              },
              icon: const Icon(Icons.add_rounded),
              label: const Text('Add custom lyrics'),
            ),
          ),
        ),

        const SizedBox(height: 4),

        // Lyrics list
        Expanded(
          child: _cachedLyrics.isEmpty
              ? _buildEmptyState(colorScheme, textTheme)
              : _filteredLyrics.isEmpty
                  ? _buildNoResultsState(colorScheme, textTheme)
                  : _buildLyricsList(colorScheme, textTheme),
        ),
      ],
    );
  }

  Widget _buildEmptyState(ColorScheme colorScheme, TextTheme textTheme) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.music_off_rounded,
            size: 64,
            color: colorScheme.onSurfaceVariant.withValues(alpha: 0.5),
          ),
          const SizedBox(height: 16),
          Text(
            'No cached lyrics',
            style: textTheme.titleMedium?.copyWith(
              color: colorScheme.onSurfaceVariant,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Lyrics will appear here as you listen to music.',
            style: textTheme.bodySmall?.copyWith(
              color: colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildNoResultsState(ColorScheme colorScheme, TextTheme textTheme) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.search_off_rounded,
            size: 64,
            color: colorScheme.onSurfaceVariant.withValues(alpha: 0.5),
          ),
          const SizedBox(height: 16),
          Text(
            'No matching lyrics stored',
            style: textTheme.titleMedium?.copyWith(
              color: colorScheme.onSurfaceVariant,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Try a different search term.',
            style: textTheme.bodySmall?.copyWith(
              color: colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLyricsList(ColorScheme colorScheme, TextTheme textTheme) {
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
      itemCount: _filteredLyrics.length,
      itemBuilder: (context, index) {
        final entry = _filteredLyrics[index];
        final title = entry['title'] as String? ?? 'Unknown';
        final artist = entry['artist'] as String? ?? 'Unknown';
        final type = entry['type'] as String? ?? 'unknown';
        final durationMs = (entry['durationMs'] as num?)?.toInt() ?? 0;
        final source = entry['source'] as String? ?? '';
        final cacheKey = entry['cacheKey'] as String;

        return Card(
          elevation: 0,
          color: colorScheme.surfaceContainerHigh,
          margin: const EdgeInsets.symmetric(vertical: 4),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            borderRadius: BorderRadius.circular(28),
            onTap: () => _editLyrics(entry),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
              child: Row(
                children: [
                  // Type indicator
                  Container(
                    width: 44,
                    height: 44,
                    decoration: BoxDecoration(
                      color: colorScheme.secondaryContainer,
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Icon(
                      type == 'synced'
                          ? Icons.sync_rounded
                          : Icons.text_snippet_rounded,
                      color: colorScheme.onSecondaryContainer,
                      size: 22,
                    ),
                  ),
                  const SizedBox(width: 14),
                  // Title and subtitle
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          '$artist · ${_formatType(type)} · ${_formatDuration(durationMs)}'
                          '${source.isNotEmpty ? ' · $source' : ''}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: textTheme.bodySmall?.copyWith(
                            color: colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                  // Action buttons
                  IconButton(
                    icon: const Icon(Icons.edit_rounded),
                    iconSize: 20,
                    tooltip: 'Edit lyrics',
                    onPressed: () => _editLyrics(entry),
                  ),
                  IconButton(
                    icon: Icon(
                      Icons.delete_outline_rounded,
                      color: colorScheme.error,
                    ),
                    iconSize: 20,
                    tooltip: 'Delete lyrics',
                    onPressed: () => _deleteLyrics(cacheKey, title),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
