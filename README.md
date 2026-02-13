<h3 align="center">
xg.glass
</h3>

<h3 align="center">
Easy, fast, glasses application development for everyone
</h3>


<p align="center">
| <a href="https://xg.glass/developer-guide/"><b>Documentation</b></a> | <a href="https://xg.glass/blog/"><b>Blog</b></a> | <a href="https://github.com/hkust-spark/xg-glass-sample/"><b>Sample Applications</b></a>
</p>

🔥 We have built a xg.glass website to help you get started with xg.glass. Please visit [xg.glass](https://xg.glass) to learn more.

---

## About

xg.glass is a fast and easy-to-use library for smart glasses application development.

Smart glasses development is supposed to be easy. If you want to build an application, all you need is the following four interfaces:

- Video input from the camera
- Audio input from the microphone
- Display output
- Audio output

This is what xg.glass has extracted for you from tens of smart glasses SDKs. We hide all details of communicating with difference glasses' SDKs and make sure that the code that you develop based on xg.glass can smoothly run on multiple glasses or a simulator without any single line of additional effort.

Currently we support:

- **Rokid**
  - Rokid Glasses
- **Brilliant Labs**
  - Frame
- **RayNeo**
  - x2 Glasses
  - x3 Pro Glasses
- **Simulation**

We're working on and will support soon:

- **INMO**
- **Omi**

Welcome the contributions from the community on more glasses!

## Getting Started

### App developers (build apps with the SDK)

#### Host machine prerequisites

- **JDK 17 or 21**
- **Android SDK + Platform Tools** (ensure `adb` is on your `PATH`)
- **Flutter** (required because the SDK embeds a Flutter module at build time for Frame)
- **Android Emulator** (for simulation mode)

The **xg-glass CLI** (see below) can automatically set up the host prerequisites above. You'll also need an Android phone with USB debugging enabled for testing on real devices.

#### Installation

Currently, the SDK is consumed from source:

```bash
git clone <this-repo>
cd <this-repo>
pip install -e .
```

If a usage menu is printed by `xg-glass --help`, the xg-glass SDK is installed successfully.

#### Quickstart

The fastest workflow is to run a single Kotlin file:

```bash
xg-glass run /path/to/MyEntry.kt
```

`MyEntry.kt` must follow a small format contract. See the [Developer Guide](https://xg.glass/developer-guide/) for details. We provide several examples at :link:[xg-glass-sample](https://github.com/hkust-spark/xg-glass-sample)

For a stable workflow, generate a minimal project and iterate:

```bash
xg-glass init /path/to/myapp
cd /path/to/myapp
xg-glass build
xg-glass install
xg-glass run
```

We also support a simulator (simply add `--sim`) for development and testing that can run on your PC with a webcam:

```bash
# Quick mode
xg-glass run --sim /path/to/MyEntry.kt

# Stable workflow
xg-glass init --sim /path/to/myapp
cd /path/to/myapp
xg-glass build
xg-glass install
xg-glass run
```

The launch of Android Emulator may take serval minutes. You can keep it on to save time for the next run. 

For more details, see the following documentation:

- [Developer Guide](https://xg.glass/developer-guide/)
- [CLI Reference](https://xg.glass/cli-reference/)

### Contributors (extend the SDK)

If you want to **extend xg.glass itself** (new devices, new APIs, build tooling), start with [Contributor Guide](https://xg.glass/contributor-guide/).
