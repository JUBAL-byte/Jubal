import 'dart:isolate';

import 'package:flutter/foundation.dart';
import 'package:spotube/services/youtube_engine/youtube_engine.dart';
// import 'package:youtube_explode_dart/solvers.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart';

import 'dart:async';

/// It contains methods that are computationally expensive
class IsolatedYoutubeExplode {
  final Isolate _isolate;
  final SendPort _sendPort;
  final ReceivePort _receivePort;

  IsolatedYoutubeExplode._(
    Isolate isolate,
    ReceivePort receivePort,
    SendPort sendPort,
  )   : _isolate = isolate,
        _receivePort = receivePort,
        _sendPort = sendPort;

  static IsolatedYoutubeExplode? _instance;

  static IsolatedYoutubeExplode get instance => _instance!;

  static bool get isInitialized => _instance != null;

  static Future<void> initialize() async {
    if (_instance != null) {
      return;
    }

    final completer = Completer<SendPort>();

    final receivePort = ReceivePort();

    /// Listen for the main isolate to set the main port
    final subscription = receivePort.listen((message) {
      if (message is SendPort) {
        completer.complete(message);
      }
    });

    final isolate = await Isolate.spawn(_isolateEntry, receivePort.sendPort);

    _instance = IsolatedYoutubeExplode._(
      isolate,
      receivePort,
      await completer.future,
    );

    if (completer.isCompleted) {
      subscription.cancel();
    }
  }

  static Future<void> _isolateEntry(SendPort mainSendPort) async {
    final receivePort = ReceivePort();
    // final solver = await DenoEJSSolver.init();
    final youtubeExplode = YoutubeExplode();
    final stopWatch = kDebugMode ? Stopwatch() : null;

    /// Send the main port to the main isolate
    mainSendPort.send(receivePort.sendPort);

    receivePort.listen((message) async {
      final SendPort replyPort = message[0];
      final String methodName = message[1];
      final List<dynamic> arguments = message[2];

      if (stopWatch != null) {
        if (stopWatch.isRunning) {
          stopWatch.stop();
          final symbol = stopWatch.elapsedMilliseconds < 1000 ? "⚠️" : "⏱️";
          debugPrint(
            "$symbol YoutubeExplode operation gap ${stopWatch.elapsedMilliseconds} ms",
          );
          stopWatch.reset();
        } else {
          stopWatch.start();
        }
      }

      // Run the requested method on YoutubeExplode.
      //
      // Every reply is an envelope: [true, value] on success, [false, message]
      // on failure. Before this, a throw in here sent nothing at all, and the
      // caller's Completer simply never completed — which is what a track
      // stuck at 0:00 with no error message actually was. YouTube changing its
      // player, an age-gated video, a region block: all of them landed here
      // and turned into a silent, permanent wait.
      try {
        var result = switch (methodName) {
          "search" => youtubeExplode.search
              .search(
                arguments[0] as String,
                filter: arguments.elementAtOrNull(1) ?? TypeFilters.video,
              )
              .then((s) => s.toList()),
          "video" => youtubeExplode.videos.get(arguments[0] as String),
          "manifest" => youtubeExplode.videos.streamsClient.getManifest(
              arguments[0] as String,
              requireWatchPage: arguments.elementAtOrNull(1) ?? true,
              ytClients: arguments.elementAtOrNull(2) as List<YoutubeApiClient>?,
            ),
          _ => throw ArgumentError('Invalid method name: $methodName'),
        };

        replyPort.send([true, await result]);
      } catch (error) {
        // The error object itself may not be sendable across isolates, so
        // only its description travels back.
        replyPort.send([false, "${error.runtimeType}: $error"]);
      }
    });
  }

  /// How long a single YouTube lookup may take before it is given up on.
  ///
  /// A request that is never answered used to leave the player waiting
  /// forever. Failing after a bounded wait lets the caller report the problem
  /// or move on to another source.
  static const _requestTimeout = Duration(seconds: 30);

  Future<T> _runMethod<T>(String methodName, List<dynamic> args) {
    final completer = Completer<T>();
    final responsePort = ReceivePort();

    responsePort.listen((message) {
      if (completer.isCompleted) return;

      if (message is List && message.length == 2 && message[0] is bool) {
        final ok = message[0] as bool;
        if (ok) {
          completer.complete(message[1] as T);
        } else {
          completer.completeError(
            Exception("YouTube lookup '$methodName' failed: ${message[1]}"),
          );
        }
      } else {
        // Tolerate a bare value, so an older reply shape still works.
        completer.complete(message as T);
      }
      responsePort.close();
    });

    _sendPort.send([responsePort.sendPort, methodName, args]);

    return completer.future.timeout(
      _requestTimeout,
      onTimeout: () {
        responsePort.close();
        throw TimeoutException(
          "YouTube lookup '$methodName' did not answer",
          _requestTimeout,
        );
      },
    );
  }

  Future<List<Video>> search(
    String query, {
    SearchFilter? filter,
  }) async {
    return _runMethod<List<Video>>("search", [query]);
  }

  Future<Video> video(String videoId) async {
    return _runMethod<Video>("video", [videoId]);
  }

  Future<StreamManifest> manifest(
    String videoId, {
    bool requireWatchPage = false,
    List<YoutubeApiClient>? ytClients,
  }) async {
    return _runMethod<StreamManifest>("manifest", [
      videoId,
      requireWatchPage,
      ytClients,
    ]);
  }

  void dispose() {
    _receivePort.close();
    _isolate.kill(priority: Isolate.immediate);
  }
}

class YouTubeExplodeEngine implements YouTubeEngine {
  static final _youtubeExplode = IsolatedYoutubeExplode.instance;

  static bool get isAvailableForPlatform => true;

  static Future<bool> isInstalled() async {
    return true;
  }

  @override
  Future<StreamManifest> getStreamManifest(String videoId) async {
    await IsolatedYoutubeExplode.initialize();

    final streamManifest = await _youtubeExplode.manifest(
      videoId,
      requireWatchPage: false,
      // Order matters: these are tried in turn.
      //
      // `androidSdkless` comes first because it is the only one of these that
      // YouTube does not demand a Proof-of-Origin token for. The plain
      // `android` client carries an `androidSdkVersion` field, and that field
      // is exactly what makes YouTube require a PO token — without one it
      // hands back 403 on every audio-only stream. Since this app plays
      // audio-only streams and nothing else, asking `android` first meant
      // asking for the one thing it cannot give, which is how a track ended
      // up sitting at 0:00 forever.
      //
      // The rest stay as fallbacks: they serve fewer or lower-quality
      // streams, but between them they cover videos the first client refuses.
      ytClients: [
        YoutubeApiClient.androidSdkless,
        YoutubeApiClient.ios,
        YoutubeApiClient.androidVr,
        YoutubeApiClient.android,
      ],
    );

    final audioStreams = streamManifest.audioOnly.where(
      (stream) => stream.bitrate.bitsPerSecond >= 40960,
    );

    return StreamManifest(
      audioStreams.map(
        (stream) => AudioOnlyStreamInfo(
          stream.videoId,
          stream.tag,
          stream.url,
          stream.container,
          stream.size,
          stream.bitrate,
          stream.audioCodec,
          switch (stream.bitrate.bitsPerSecond) {
            > 130 * 1024 => "high",
            > 64 * 1024 => "medium",
            _ => "low",
          },
          stream.fragments,
          stream.codec,
          stream.audioTrack,
        ),
      ),
    );
  }

  @override
  Future<Video> getVideo(String videoId) async {
    await IsolatedYoutubeExplode.initialize();
    return _youtubeExplode.video(videoId);
  }

  @override
  Future<(Video, StreamManifest)> getVideoWithStreamInfo(String videoId) async {
    await IsolatedYoutubeExplode.initialize();

    final video = await getVideo(videoId);
    final streamManifest = await getStreamManifest(videoId);

    return (video, streamManifest);
  }

  @override
  Future<List<Video>> searchVideos(String query) async {
    await IsolatedYoutubeExplode.initialize();

    return _youtubeExplode
        .search(
          query,
          filter: TypeFilters.video,
        )
        .then((searchList) => searchList.toList());
  }

  @override
  void dispose() {
    IsolatedYoutubeExplode.instance.dispose();
  }
}
