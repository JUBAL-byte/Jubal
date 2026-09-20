import 'package:drift/drift.dart';

import 'package:spotube/models/database/database.dart';
import 'package:spotube/services/kv_store/kv_store.dart';
import 'package:spotube/services/logger/logger.dart';

/// A watchdog that keeps a bad setting from making Jubal impossible to open.
///
/// Some settings choose machinery that runs while the app is starting — the
/// YouTube engine most of all. If that machinery fails hard enough, the app
/// dies before anyone can reach Settings to undo the choice, and the only way
/// back is to erase the app's data and sign in again.
///
/// So each launch leaves a mark on the way in and clears it once the app has
/// been up and drawing for a while. Finding that mark still set at the next
/// launch is proof that the previous one never got that far, and the settings
/// that can break a launch are returned to their safe defaults before anything
/// reads them.
///
/// The mark is cleared only after a delay, not at the first frame, because a
/// crash of this kind usually arrives a moment after the window appears —
/// while the first screen resolves whatever was playing last.
abstract class StartupGuard {
  /// How long the app must keep running before a launch counts as healthy.
  static const _settleDelay = Duration(seconds: 12);

  static bool _previousLaunchFailed = false;

  /// Whether the previous launch died before the app was up and running.
  static bool get previousLaunchFailed => _previousLaunchFailed;

  /// Records that a launch has begun. Call once [KVStoreService] is ready.
  static Future<void> begin() async {
    _previousLaunchFailed = KVStoreService.startupIncomplete;

    if (_previousLaunchFailed) {
      AppLogger.log.w(
        "[StartupGuard] previous launch did not complete — "
        "restoring default playback settings",
      );
    }

    await KVStoreService.setStartupIncomplete(true);
  }

  /// Undoes the settings that can prevent the app from opening.
  ///
  /// Only the playback engine is reset. It is the one preference that runs
  /// native code during startup, and its default is the engine that ships
  /// working on every platform.
  static Future<void> recover(AppDatabase database) async {
    if (!_previousLaunchFailed) return;

    try {
      await (database.update(database.preferencesTable)
            ..where((tbl) => tbl.id.equals(0)))
          .write(
        const PreferencesTableCompanion(
          youtubeClientEngine: Value(YoutubeClientEngine.youtubeExplode),
        ),
      );
    } catch (error, stack) {
      // A failed recovery must never itself stop the app from opening.
      AppLogger.reportError(error, stack);
    }
  }

  /// Clears the mark once the app has stayed up long enough to be trusted.
  static void markHealthyWhenSettled() {
    Future.delayed(_settleDelay, () async {
      try {
        await KVStoreService.setStartupIncomplete(false);
      } catch (error, stack) {
        AppLogger.reportError(error, stack);
      }
    });
  }
}
