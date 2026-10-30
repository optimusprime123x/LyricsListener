// Matches the timestamp forms accepted by Android's custom LRC parser.
final _timestampPattern = RegExp(r'^(\d+):(\d{1,2})(?:[.:](\d{1,3}))?$');
final _lrcTimestampPattern = RegExp(r'\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?\]');

String normalizeLrcInput(String text) => text
    .replaceAll('\uFEFF', '')
    .replaceAll('\uFF3B', '[')
    .replaceAll('\uFF3D', ']')
    .replaceAll('\uFF1A', ':')
    .replaceAll('\uFF0E', '.')
    .replaceAll('\u3000', ' ')
    .replaceAll('\u00A0', ' ');

int? parseLyricsTimestamp(String text) {
  final match = _timestampPattern.firstMatch(normalizeLrcInput(text).trim());
  if (match == null) return null;
  final minutes = int.tryParse(match[1]!);
  final seconds = int.parse(match[2]!);
  // Android parses minutes as a signed 32-bit integer before converting to milliseconds.
  if (minutes == null || minutes > 0x7fffffff || seconds > 59) return null;
  final fraction = match[3];
  final milliseconds = fraction == null
      ? 0
      : int.parse(fraction.padRight(3, '0'));
  return (minutes * 60 + seconds) * 1000 + milliseconds;
}

String formatLyricsTimestamp(int milliseconds) {
  final minutes = (milliseconds ~/ 60000).toString().padLeft(2, '0');
  final seconds = (milliseconds ~/ 1000 % 60).toString().padLeft(2, '0');
  final fraction = (milliseconds % 1000).toString().padLeft(3, '0');
  return '$minutes:$seconds.$fraction';
}

int countLrcTimestamps(String text) => _lrcTimestampPattern
    .allMatches(normalizeLrcInput(text))
    .where(
      (match) =>
          parseLyricsTimestamp(match[0]!.substring(1, match[0]!.length - 1)) !=
          null,
    )
    .length;
