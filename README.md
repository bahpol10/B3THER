<h1 align="center">B3THER</h1>

<p align="center">
  A modern Android VPN client built on the <b>Aether core</b> — fast, clean,
  and made to be simple: pick a mode, tap connect, done.
</p>

<p align="center">
  <a href="README.md">🇬🇧 English</a> · <a href="README.fa.md">🇮🇷 فارسی</a>
</p>

<p align="center">
  <img alt="version" src="https://img.shields.io/badge/version-1.6.0-00E5C0?style=flat-square">
  <img alt="engine core" src="https://img.shields.io/badge/engine%20core-2.4.0-FFB020?style=flat-square">
  <img alt="min Android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
  <img alt="license" src="https://img.shields.io/badge/license-AGPL--3.0-F59E0B?style=flat-square">
</p>

---

## About

**B3THER** is my own Android VPN app, forked from
[Aether Mobile](https://github.com/QW-AI-Code/Aether) (by QW-AI-Code /
Cluvex Studio) and rebuilt around a simpler, modern interface: a graphite
and electric-teal design, a big animated connect button, live connection
stats, and nothing you don't need. The engine underneath is the Aether
core (2.4.0) — the same battle-tested tunnel, with my own face on it.

## Features

- **Six connection modes** — Aether (WARP), Aether → Psiphon, Tor,
  Aether → Tor, Tor → Psiphon, Tor → Aether. Switch paths depending on
  what your network allows.
- **Live connection card** — session timer, exit IP with country flag,
  real-time download/upload speeds, pipeline and latency.
- **Per-app split tunnelling** — choose which apps go through the VPN.
- **Kill switch** — traffic stops if the tunnel drops.
- **DNS, MTU, fragmentation and ECH settings** for tricky networks.
- **Quick Settings tile and home-screen widget** — connect in one tap.
- **Gemini assistant** — bring your own free API key from Google AI Studio.
- **Persian and English** interface, full RTL support.
- **Encrypted diagnostics log** — for debugging without leaking details.

## Download

Get the latest APK from the
[Releases page](https://github.com/bahpol10/B3HMAN/releases)
(`arm64-v8a` fits most phones; `universal` works everywhere). Install it
on your phone and allow installs from unknown sources when asked.

## Build it yourself

- **GitHub Actions** — every push to `main` builds the APKs and publishes
  them as a Release (`.github/workflows/build.yml`).
- **Android Studio** — open this project, let Gradle sync, and run it.
  The native engine is cross-compiled by `scripts/build-natives.sh`
  (needs the Android NDK + Rust toolchain); see `README-B3HMAN.md` for
  the full build guide and signing notes.

## Credits & license

B3THER is a fork of **Aether Mobile 1.6.0** by QW-AI-Code (Cluvex
Studio), used under the **AGPL-3.0-or-later** license. All credit for
the Aether engine and the original app goes to its authors; see
`REBRAND.md` and `THIRD_PARTY_NOTICES.md` for exactly what was changed
in this fork. This project is licensed under AGPL-3.0-or-later — see
`LICENSE`.
