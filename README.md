# Unonime

A native Android AniList client built with Kotlin and Jetpack Compose. It does not use WebView.

## Included
- AniList-powered Home feed (trending, popular, top rated)
- Anime search and detail pages
- Native image loading and dark mobile interface
- AniList OAuth implicit sign-in support
- Add anime to Currently Watching and view your anime list
- GitHub Actions debug APK build

## Sign in setup
1. Open AniList Developer Settings: https://anilist.co/settings/developer
2. Create an application and set Redirect URL to exactly `unonime://auth`.
3. Copy its Client ID into Unonime > Profile > AniList Client ID.
4. Tap Login with AniList.

Public catalogue browsing works without signing in. Private list features require an AniList account connection.

## Build
Pushes to `main` build a debug APK. Download the `unonime-debug-apk` artifact from the successful GitHub Actions run.
