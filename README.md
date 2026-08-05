# @anorak-games/react-native-video-texture

**NOTE:** This is a very experimental package that is unlikely to be useful after 2026. It has known issues like leaking on disk cache, and it requires a very specific webgpu version + patch It exists mostly as a temporary bridge between react-native-webgpu's external texture feature and the fact that expo-video/skia did not support usage at time of creation. There is probably a better way to do this by now.

Headless Expo video playback that exposes decoded frames as native buffers for
`react-native-webgpu` external textures.

## Requirements

- Expo 56 or newer and a development build; this module is not available in Expo Go.
- `react-native-webgpu@0.8.1` with the bundled patch described below.
- iOS 16.4 or newer.
- A physical Android device running API 29 or newer. Android emulators are not supported.

After installing or upgrading this package, regenerate and rebuild the native app with
`npx expo prebuild --clean` followed by `npx expo run:ios` or `npx expo run:android`.

## Install

```sh
npm install @anorak-games/react-native-video-texture react-native-webgpu@0.8.1
npm install --save-dev patch-package
```

Set the consuming app's `postinstall` script to:

```json
{
  "scripts": {
    "postinstall": "patch-package --error-on-fail --patch-dir node_modules/@anorak-games/react-native-video-texture/patches"
  }
}
```

The patch is tied to `react-native-webgpu@0.8.1`. It releases the native external texture
and source frame when `GPUExternalTexture.destroy()` is called (so the frame buffer returns
to its producer immediately), surfaces native error messages to JS instead of
`Exception in HostFunction: <unknown>`, acquires shared textures with a content-preserving
image layout, and logs the driver-reported import properties once per process
(`[huntVideoColor]` tag) for colour debugging. `patches/dawn/` is reference material for
upstream Dawn reports and is not applied to anything.

Apps using Expo prebuild should set the native minimum versions explicitly:

```ts
export default {
  ios: {
    deploymentTarget: '16.4',
  },
  plugins: [
    'react-native-webgpu',
    [
      'expo-build-properties',
      {
        android: {
          minSdkVersion: 29,
        },
      },
    ],
  ],
};
```

## Usage

Request the native Dawn features alongside the application's other WebGPU features:

```ts
import {
  VIDEO_TEXTURE_DEVICE_FEATURES,
  isVideoTextureSupported,
  useVideoTexture,
} from '@anorak-games/react-native-video-texture';

const device = await adapter.requestDevice({
  requiredFeatures: [...VIDEO_TEXTURE_DEVICE_FEATURES],
});
```

`useVideoTexture()` returns a player and a worklet-capturable frame source. Load transport
commands through the player, poll decoded frames from the frame source inside the render
worklet, wrap each native handle with `RNWebGPU.createVideoFrameFromNativeBuffer()`, then
import it with `device.importExternalTexture()`.

Check `isVideoTextureSupported()` before entering a native video path. Importing the package
is safe on web, but creating a player is not supported there.

## Platform notes

- Samples arrive as RGB on both platforms — Android converts decoder frames to RGBA8 on the
  GPU (`GlVideoBridge`, one blit per frame), iOS converts in Dawn's Metal sampler — so shaders
  need **no** colour conversion and no platform branches. `GPUExternalTexture.yuvToRgbMatrix`
  is the identity for every frame this module produces.
- Android reports `pixelFormat: 'bgra8'` (`'nv12'` is accepted at construction as a legacy
  alias) and rejects rotated video.
- Keep the external video texture and its sampler in bind group 0; place other resources in
  later bind groups.
## Development

```sh
npm install
npm run build
npm test
npm pack --dry-run
```

After editing `node_modules/react-native-webgpu`, regenerate the bundled patch with:

```sh
npm run patch:webgpu
```

## Releasing

Merge to main, then create a Github Release with appropriate version tag (ie v0.4.0), Github Action will publish from that.

Manual release via `npm release:minor` if you have credentials, but you should prefer the github flow.
