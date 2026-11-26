# Galaxy Camera Tools

Installation requires Android 17 (API 37) or later.

[日本語](README.md) · English

An experimental Vector module extending Samsung Camera, Camera Assistant, and Expert RAW. Features can be switched independently; settings are synchronized between the companion app and Camera Assistant.

## First unlock after boot

Hook initialization that needs settings or cached discovery results waits for the first user unlock after boot. A later ordinary screen lock does not repeat initialization. Existing screen-off power-saving and security behavior is preserved. Unlock delivery is event-driven, with no polling or new resident service.

## Features

| Feature | Behavior |
| --- | --- |
| Photo preview | Request 60 fps for supported normal-photo previews, returning to stock rates during thermal protection |
| Lighter photo preview | Lower the display resolution of supported rear-camera photo previews without lowering saved-photo resolution |
| Stock-camera lens switching | Add slightly slower easing to transitions in photo, video, Super Steady, and horizon-lock modes |
| 120 fps in normal video | Add supported rear-camera FHD/UHD recording configurations to normal Video mode |
| Ultrawide light | Use stock flash/video lighting with the rear ultrawide lens |
| Focus lock during flash | Hold the pre-flash focus position and restore autofocus after capture or cancellation |
| Quick Panel torch | Control the torch while rear-camera photo or normal video mode is in use |
| Expert RAW lens switching | Match the stock camera's zoom curve and duration, transitioning from a scaled view to the new lens image |
| Camera2 in ordinary apps | Expose additional rear lenses to supported camera apps |

## Requirements

The initial experimental target is Galaxy S26 Ultra (SM-S948Q), Android 17 / One UI 9 BZI9, with Samsung Camera 17.0.00.43, Camera Assistant 5.0.00.7, and Expert RAW 6.0.02.8. A supported root environment and Vector with libxposed API 102 are required.

Unverified capture paths are not applied to other devices, firmware, or target versions.

## Installation and use

1. Install the signed release APK from [Releases](https://github.com/OneUI-Mods/GalaxyCameraTools/releases).
2. Enable Galaxy Camera Tools in Vector and select targets according to the table below.
3. Force-stop and reopen affected apps after installation or an APK update. Reload SystemUI after initial enablement or updates; [Galaxy Reload](https://github.com/OneUI-Mods/galaxy-reload) can do this.
4. Enable the desired switches in the companion app or Camera Assistant. All features default to OFF.

| Features | Vector scope |
| --- | --- |
| Photo/video, ultrawide light, focus lock | Samsung Camera |
| Additional Camera Assistant settings | Camera Assistant |
| RAW lens transitions | Expert RAW |
| Quick Panel torch | Samsung Camera and SystemUI |
| Additional lenses in ordinary apps | Add each camera app you intend to use |

After updating, open the stock camera once before using Expert RAW. Stock-camera and Expert RAW lens-transition switches are separate, and independent of **60 fps photo preview**. With both stock-camera options enabled for rear photos, the preceding image is scaled at 60 fps before handing over to the new lens. Updates from older versions carry forward the stock-animation enabled state once.

For additional Camera2 lenses, grant camera permission to the target and reopen it. If choices do not appear initially, reopen it again. Close and reopen the active camera after changing settings. An app without a lens-selection UI cannot select those lenses.

## Limitations and checks before use

- 120 fps covers supported rear-camera FHD/UHD 16:9 configurations. It is not added to 8K, HD, front-camera, or unusual-aspect-ratio modes. Saved video with audio has been checked, but long-recording limits and heat depend on the stock camera and device.
- Photo transitions temporarily reuse the preceding image, so moving subjects may briefly appear frozen. This does not make every sensor-capture path run at 60 fps. Capture failure falls back to the original transition.
- 60 fps photo preview is limited to supported image sizes. Front-camera use and rear-lens transitions can still run at 30 fps. Darkness, temperature, and camera processing also affect the actual rate. Samsung's thermal protections remain enabled.
- **Lighter photo preview** lowers the preview resolution of supported rear normal-photo modes. Display detail is reduced; saved-photo resolution is unchanged. Reopen the camera to apply it.
- Flash focus lock holds the position from before illumination. Refocus before shooting if the subject moves. Normal autofocus is preserved.
- Quick Panel torch support targets rear photo and normal-video modes. A torch enabled through this feature is switched off when leaving the camera. Stock temperature and battery protections remain intact.
- Additional lenses in ordinary apps target standard Camera2 capture paths. Main, 3×, and 5× previews and JPEG capture from additional telephoto lenses have been checked. This does not establish support for every app, RAW capture, special sessions, or high-resolution/high-fps combinations. The module does not create unsupported resolutions or rates; camera permission is still required.
- Stabilization/horizon-lock resolutions and recording rates remain stock. Adding an animation does not enable unsupported shooting conditions.
- If an Expert RAW preview cannot be obtained, the module falls back to normal lens switching rather than waiting indefinitely.

Before important shooting, make a short test to verify video, audio, and the features you need.

## Disabling and recovery

Disable the affected feature and reopen the target app. For complete removal, disable the module in Vector and reload its target processes.

Repeated crashes shortly after module operations stop modifications to that app. Resolve the cause, reset protection under **Status / About**, and reopen the target to retry.

## Development and licenses

Build instructions, design, and validation are in the [developer guide (Japanese)](docs/development.md). Report problems through [Issues](https://github.com/OneUI-Mods/GalaxyCameraTools/issues), with device, OS and target versions, and reproduction steps.

The project uses the [MIT License](LICENSE). Dependency licenses are searchable in the app. Samsung app binaries, images, and code are not bundled.

[OneUI Mods app catalog](https://github.com/OneUI-Mods)

## String compatibility

Handling of empty strings and certain whitespace has been corrected in video-mode compatibility information and license search.

## Compatibility after updates

After a target-app update, open this module once to complete preparation, then reopen the target. Discovery results are stored in this module's own internal app data and reused while unchanged. No other OneUI Mods app is required. Features stop applying when a unique compatible target cannot be confirmed. Compatibility with every OS or app update is not guaranteed.

[Build environment / ビルド環境](docs/build-environment.md)
