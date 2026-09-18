/// Browsing-free build.
///
/// This app must never become a way out to the open web. Upstream Spotube opens
/// external links — repository pages, donation pages, Discord invites, plugin
/// homepages, links inside plugin descriptions — through `url_launcher`, which
/// hands the URL to the system browser.
///
/// This file is a drop-in replacement for the two `url_launcher` entry points
/// the app used. Every call site keeps compiling unchanged, but no URL is ever
/// handed to a browser: the functions simply report failure. The `url_launcher`
/// package is no longer imported anywhere in `lib/`.
///
/// What this does NOT do, and should not be mistaken for: the app still makes
/// its own network requests, because it cannot fetch music metadata or audio
/// without them. What is blocked is the app being used to *browse* — to open an
/// arbitrary page, follow a link, or render web content chosen by a link.
library;

/// Mirrors `url_launcher`'s enum so existing call sites still type-check.
enum LaunchMode {
  platformDefault,
  inAppWebView,
  inAppBrowserView,
  externalApplication,
  externalNonBrowserApplication,
}

/// Always refuses. Returns false, the same value `url_launcher` returns when
/// no handler is available, so callers that check the result behave sanely.
Future<bool> launchUrl(
  Uri url, {
  LaunchMode mode = LaunchMode.platformDefault,
  Object? webViewConfiguration,
  Object? browserConfiguration,
  String? webOnlyWindowName,
}) async {
  return false;
}

/// Always refuses. See [launchUrl].
Future<bool> launchUrlString(
  String url, {
  LaunchMode mode = LaunchMode.platformDefault,
  Object? webViewConfiguration,
  Object? browserConfiguration,
  String? webOnlyWindowName,
}) async {
  return false;
}

/// Reports that nothing can be launched, so any UI that hides a link when it
/// cannot be opened will hide it.
Future<bool> canLaunchUrl(Uri url) async => false;

/// See [canLaunchUrl].
Future<bool> canLaunchUrlString(String url) async => false;
