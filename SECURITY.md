# Security Policy

## Supported Versions

| Version | Supported |
| --- | --- |
| 0.1.x | Yes |

## Reporting a Vulnerability

Please use GitHub private vulnerability reporting as the primary channel:

1. Open the repository **Security** tab.
2. Select **Report a vulnerability**.
3. Include affected package/channel, version, platform, device model, reproduction steps, and any logs or proof-of-concept code that can be shared safely.

If GitHub private vulnerability reporting is unavailable, use GitHub's report-abuse flow and include that this concerns `hkust-spark/xg-glass-sdk`.

## Response Targets

- We aim to acknowledge reports within 7 days.
- We aim to provide a fix or mitigation plan within 90 days.
- Camera, microphone, and AI API-key handling reports are treated with priority.

## Scope

In scope:

- SDK modules in this repository.
- The `xg-glass` CLI.
- Published Maven Central, Swift Package, and PyPI artifacts.
- Security issues in camera/microphone data handling or encrypted app-contract settings storage.

Out of scope:

- Vendor SDKs and binaries, including Rokid, Meta, and RayNeo components. Report those directly to the vendor.
- Issues that require unsupported local modifications or compromised developer machines.
