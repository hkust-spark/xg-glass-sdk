<h3 align="center">
xg.glass
</h3>

<h3 align="center">
Easy, fast, glasses application development for everyone
</h3>


<p align="center">
| <a href="https://xg.glass/developer-guide/"><b>Documentation</b></a> | <a href="https://xg.glass/blog/"><b>Blog</b></a> |
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

This is what xg.glass has extracted for you from tens of smart glasses SDKs. We hide all details of communicating with difference glasses' SDKs and make sure that the code that you develop based on xg.glass can smoothly run on multiple glasses without any single line of additional effort.

Currently we support:

- **Rokid**
  - Rokid Glasses
- **Brilliant Labs**
  - Frame
- **RayNeo**
  - x2 Glasses
  - x3 Pro Glasses

We're working on and will support soon:

- **INMO**
- **Omi**

Welcome the contributions from the community on more glasses!

## Getting Started

### App developers (build apps with the SDK)

If you are an **app developer**, start with:

- [Developer Guide](https://xg.glass/developer-guide/)
- [CLI Reference](https://xg.glass/cli-reference/)

#### Prerequisites

- **JDK 17**
- **Android SDK + platform tools** (you need `adb` on your `PATH`)
- **An Android phone** with USB debugging enabled
- **Flutter** is required on your machine since the SDK embeds a Flutter module at build time for Frame

#### Installation

Currently, the SDK is consumed from source:

```bash
git clone <this-repo>
cd <this-repo>
./xg-glass --help
```

Required for **RayNeo**:

- Download the vendor AARs (not redistributed in this repo):
  - [`MercuryAndroidSDK*.aar`](https://leiniao-ibg.feishu.cn/wiki/ZK2LwOtoSi42DckWtuGc91wkn8e)
  - [`RayNeoIPCSDK*.aar`](https://leiniao-ibg.feishu.cn/file/Ctaibd0xNozMjvx3amTcCShgn7d)
- Place them under `./third_party/rayneo/aar/`.

#### Quickstart

The fastest workflow is to run a single Kotlin file:

```bash
./xg-glass run /path/to/MyEntry.kt
```

`MyEntry.kt` must follow a small format contract. See the [Developer Guide](https://xg.glass/developer-guide/) for details.

For a stable workflow, generate a minimal project and iterate:

```bash
./xg-glass init /path/to/myapp
cd /path/to/myapp
/path/to/xg-glass-sdk/xg-glass build
/path/to/xg-glass-sdk/xg-glass install
/path/to/xg-glass-sdk/xg-glass run
```

### Contributors (extend the SDK)

If you want to **extend xg.glass itself** (new devices, new APIs, build tooling), start with [Contributor Guide](https://xg.glass/contributor-guide/).


