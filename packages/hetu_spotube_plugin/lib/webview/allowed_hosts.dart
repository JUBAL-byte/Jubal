/// Sign-in allowlist for Jubal's embedded webview.
///
/// The embedded webview exists for exactly one purpose: signing in to a music
/// metadata provider. It must not become a general-purpose browser. Everything
/// here is therefore closed by default — a page may only be opened if its host
/// is on this list.
///
/// The list covers the providers themselves, the identity providers they hand
/// off to (Google, Facebook, Apple), and the asset / captcha / verification
/// hosts those login pages genuinely need in order to render and complete.
/// Nothing else loads.
///
/// To allow another provider later, add its domain here — matching is by
/// domain suffix, so adding `example.com` also allows `accounts.example.com`.
library;

/// Hosts that may be opened in the sign-in webview, matched by domain suffix.
const List<String> kAllowedSignInDomains = [
  // --- Spotify: the provider itself ---------------------------------------
  'spotify.com', // accounts., open., www., challenge. (verification)
  'spotifycdn.com', // login page styling and scripts
  'scdn.co', // images referenced by the login page

  // --- Google: "Continue with Google" -------------------------------------
  'google.com', // accounts.google.com, www.google.com/recaptcha
  'googleapis.com',
  'gstatic.com', // fonts and scripts the Google login page loads
  'googleusercontent.com', // profile images
  'recaptcha.net', // captcha fallback host

  // --- Facebook: "Continue with Facebook" ---------------------------------
  'facebook.com', // www. and m.
  'facebook.net', // connect.facebook.net
  'fbcdn.net', // static assets

  // --- Apple: "Continue with Apple" ---------------------------------------
  'appleid.apple.com',
  'cdn-apple.com',

  // --- Bot checks and consent shown during sign-in and sign-up -------------
  // These belong to the login flow itself; without them a captcha or a
  // cookie banner can stall the page before credentials are even accepted.
  'hcaptcha.com',
  'arkoselabs.com',
  'funcaptcha.com',
  'onetrust.com',
  'cookielaw.org',

  // --- Spotify's own short links, used by the sign-up path ----------------
  'spotify.link',
  'onelink.me',
];

/// Whether [url] may be opened in the sign-in webview.
///
/// Only `http` and `https` are filtered by the allowlist. Other schemes are
/// left alone because an OAuth flow finishes by redirecting to a custom scheme
/// (for example `spotube://callback`), and that redirect is how the plugin
/// learns that sign-in succeeded. Such a redirect never renders a page.
bool isSignInUrlAllowed(Uri url) {
  final scheme = url.scheme.toLowerCase();
  if (scheme != 'http' && scheme != 'https') {
    return true;
  }

  final host = url.host.toLowerCase();
  if (host.isEmpty) return false;

  for (final domain in kAllowedSignInDomains) {
    if (host == domain || host.endsWith('.$domain')) {
      return true;
    }
  }
  return false;
}
