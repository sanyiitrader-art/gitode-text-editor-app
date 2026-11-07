# Gitode

Gitode is an independent Android text/code editor with GitHub integration:
Git push, GitHub Actions build monitoring, artifact downloads, project file
management and a recycle bin. It is not affiliated with or endorsed by GitHub.

Gitode has no backend server. Compiling happens remotely in GitHub Actions.

## Building the APK (no local Android tools needed)

1. Put every file of this repository on GitHub, including your `icon.png` in the project root.
2. Open the repository's **Actions** tab, choose **Build Gitode** and press **Run workflow**.
3. When the run is green, open it and download the artifact **Gitode-apks** (one zip).
4. Unzip it. Install `Gitode-universal.apk` on any phone, or the file matching your ABI
   (`Gitode-arm64-v8a.apk` fits most modern phones).

The workflow starts only when you press the button, never on push.

Gitode needs Android 9 (API 28) or newer. Development builds are signed with a
throwaway key created on every run, so uninstall the previous test build before
installing a new one.

## What the build prepares automatically (tools/prepare.py)

- Launcher icons from `icon.png`: adaptive icon, artwork scaled uniformly into the
  safe zone, transparency preserved, no background colour added, nothing cropped.
- TextMate grammars and themes (tm-grammars and tm-themes packages).
- Editor fonts from the google/fonts repository.
- Language logos for the file explorer (devicon and simple-icons).
- Theme preview screenshots from `theme-previews/`.

These are downloaded or generated during the build and are not committed.
If a font or logo cannot be downloaded the build still succeeds and the app falls
back to a system font or a generic icon. The build log lists every warning.

## Theme preview screenshots

Put one PNG per theme into the `theme-previews/` folder, named with the theme id:

- Dark: `obsidian.png`, `charcoal.png`, `midnight.png`, `nord.png`, `dracula.png`,
  `one-dark.png`, `amoled-black.png`, `forest.png`, `graphite.png`, `warm-ember.png`
- Light: `paper.png`, `github-light.png`, `solarized-light.png`, `rose-pine-dawn.png`

Lowercase names, no source-code changes needed. Themes without a screenshot show
the placeholder text "Later".

## Signing

- Development: `build.yml` creates a temporary keystore on each run and passes it to
  Gradle through environment variables. No secrets are needed.
- Release: configured at the end of Phase 3 with a permanent keystore stored in
  GitHub Secrets and a separate `release.yml` workflow. Development builds never
  depend on those secrets. Never commit a keystore.

## Third-party components and licenses

| Component | Used for | License |
|---|---|---|
| Sora Editor (Rosemoe) | code editor, TextMate engine | LGPL-2.1 |
| Eclipse JGit | Git operations | EDL (BSD-3-Clause) |
| tm-grammars, tm-themes | grammars and themes (each grammar keeps its original license) | MIT |
| JetBrains Mono, Fira Code, Source Code Pro, IBM Plex Mono, Roboto Mono, Cascadia Code, Inconsolata, Noto Sans Mono | editor fonts | SIL OFL 1.1 |
| Ubuntu Mono | editor font | Ubuntu Font Licence 1.0 |
| devicon | language logos | MIT |
| simple-icons | TOML, Godot and React logos | CC0 |
| AndroidX, Jetpack Compose, Kotlin, Kotlin coroutines, WorkManager | app framework | Apache-2.0 |

Brand names and logos belong to their respective owners and are used only to
identify file types.