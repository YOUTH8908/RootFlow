# RootFlow

English | [简体中文](README.zh-CN.md)

RootFlow is an Android root-based proxy forwarding app and LSPosed module. It runs the bundled Mihomo core and redirects traffic for supported Google system apps through an imported Clash-compatible configuration.

![RootFlow configuration screen](docs/images/rootflow-config.png)

## Features

- Import Clash-compatible YAML files, HTTP(S) subscriptions, and supported proxy share links.
- Inspect imported proxies and proxy groups, and choose a proxy for the `Google Proxy` group.
- Run the Mihomo core as a foreground service and apply root-level traffic redirection for supported Google packages.
- Optionally resume after device boot and recover after another VPN disconnects. Android and device-vendor background-start restrictions may require enabling the app's autostart permission.
- Report LSPosed framework connection and module-scope status in the app.

The app requires Android 13 or later, a working root manager, and LSPosed with this module enabled for the intended scope. RootFlow does not provide proxy servers or subscriptions. Compatibility depends on the imported configuration, Mihomo, Android version, root implementation, LSPosed, and other VPN or firewall software.

## Build

Open the project in Android Studio with a compatible Android Gradle Plugin and JDK, or build the debug APK with the included Gradle wrapper:

```sh
./gradlew assembleDebug
```

The APK is generated under `app/build/outputs/apk/debug/`.

## Disclaimer

Read the [Disclaimer](DISCLAIMER.md) before installing or using RootFlow. Root access changes device networking behavior and can affect connectivity or device stability.

## License

No project-level license is currently included. Unless a license is added to this repository, you may not assume permission to redistribute or modify RootFlow. The bundled Mihomo core and other dependencies are governed by their respective licenses.
