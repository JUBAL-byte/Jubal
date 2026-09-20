import 'package:flutter/material.dart';
import 'package:just_audio/just_audio.dart';

import 'source/youtube.dart';

void main() => runApp(const JubalApp());

/// Jubal: pure audio experience.
///
/// Text only, by construction — there is no widget in this app that draws an
/// image, and nothing that opens a web page. What is not built cannot be
/// reached.
class JubalApp extends StatelessWidget {
  const JubalApp({super.key});

  @override
  Widget build(BuildContext context) {
    const ink = Color(0xFF14141A);
    const cream = Color(0xFFF2F0EC);

    return MaterialApp(
      title: "Jubal",
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: ink,
        colorScheme: const ColorScheme.dark(
          surface: ink,
          primary: cream,
          onPrimary: ink,
        ),
      ),
      home: const SearchPage(),
    );
  }
}

class SearchPage extends StatefulWidget {
  const SearchPage({super.key});

  @override
  State<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends State<SearchPage> {
  final _player = AudioPlayer();
  final _field = TextEditingController();

  List<Song> _results = const [];
  Song? _playing;
  String? _error;
  bool _searching = false;
  bool _loadingTrack = false;

  @override
  void dispose() {
    _player.dispose();
    _field.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final query = _field.text.trim();
    if (query.isEmpty) return;

    setState(() {
      _searching = true;
      _error = null;
    });

    try {
      final songs = await YouTube.search(query);
      if (!mounted) return;
      setState(() {
        _results = songs;
        _error = songs.isEmpty ? "Nothing found for “$query”." : null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  Future<void> _play(Song song) async {
    setState(() {
      _loadingTrack = true;
      _playing = song;
      _error = null;
    });

    try {
      final url = await YouTube.streamUrl(song.id);
      await _player.setUrl(url);
      await _player.play();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error.toString();
        _playing = null;
      });
    } finally {
      if (mounted) setState(() => _loadingTrack = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: TextField(
                controller: _field,
                textInputAction: TextInputAction.search,
                onSubmitted: (_) => _search(),
                decoration: InputDecoration(
                  hintText: "Search for a song",
                  border: const OutlineInputBorder(),
                  suffixIcon: IconButton(
                    icon: const Icon(Icons.search),
                    onPressed: _search,
                  ),
                ),
              ),
            ),
            if (_searching) const LinearProgressIndicator(minHeight: 2),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: Text(
                  _error!,
                  style: const TextStyle(fontFamily: "monospace", fontSize: 13),
                ),
              ),
            Expanded(
              child: ListView.separated(
                itemCount: _results.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (context, index) {
                  final song = _results[index];
                  final isPlaying = _playing?.id == song.id;
                  return ListTile(
                    title: Text(song.title, maxLines: 2),
                    subtitle: Text(
                      [song.artist, song.length]
                          .where((part) => part.isNotEmpty)
                          .join(" · "),
                    ),
                    trailing: isPlaying
                        ? const Text("playing", style: TextStyle(fontSize: 12))
                        : null,
                    onTap: _loadingTrack ? null : () => _play(song),
                  );
                },
              ),
            ),
            _NowPlaying(player: _player, song: _playing, loading: _loadingTrack),
          ],
        ),
      ),
    );
  }
}

class _NowPlaying extends StatelessWidget {
  final AudioPlayer player;
  final Song? song;
  final bool loading;

  const _NowPlaying({
    required this.player,
    required this.song,
    required this.loading,
  });

  @override
  Widget build(BuildContext context) {
    final current = song;
    if (current == null) return const SizedBox.shrink();

    return Container(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 14),
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: Colors.white24)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(current.title, maxLines: 1, overflow: TextOverflow.ellipsis),
                Text(
                  loading ? "loading…" : current.artist,
                  style: const TextStyle(fontSize: 12, color: Colors.white70),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
          StreamBuilder<PlayerState>(
            stream: player.playerStateStream,
            builder: (context, snapshot) {
              final playing = snapshot.data?.playing ?? false;
              return IconButton(
                icon: Icon(playing ? Icons.pause : Icons.play_arrow),
                onPressed: playing ? player.pause : player.play,
              );
            },
          ),
        ],
      ),
    );
  }
}
