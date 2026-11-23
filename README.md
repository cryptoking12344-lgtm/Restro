# Restro

AI photo restoration for Android that runs entirely on the phone: upscale to 4K, clean noise and blur, and rebuild faces. It uses the same look as Orbit EQ (Liquid Glass, Orbit and Midnight themes).

## What it does

| Option | Choices |
|---|---|
| **Restore mode** (presets) | Quick · Photo · Portrait · Old photo · Anime |
| **Output size** | Original (cleaned) · 2× · 4× · 4K (3840 px long side); up to 7680 px |
| **AI engine** | **Fast** (Real-ESRGAN general v3, built in) · **Ultra detail** (Real-ESRGAN x4plus, 34 MB download) · **Anime** (Real-ESRGAN anime v3, built in) |
| **Noise cleanup** (Fast) | Keep grain · Balanced · Clean |
| **Restore faces** | GFPGAN 1.4 (170 MB download), strength 0–100 %, face detail boost |
| **Finish** | Sharpen 0–100 %, Auto colour (revives faded photos), JPEG or PNG |

Other features: a before/after wipe viewer with pinch zoom, a progress notification with time left (you can leave the app while it works), saving to `Pictures/Restro`, a Library tab, and "Share → Restore with Restro" from any gallery.

## How it works

- Photos are processed in overlapping tiles with ONNX Runtime (XNNPACK), so even 4K output fits in memory.
- ML Kit finds faces and their landmarks. Each face is aligned to the FFHQ template, restored by GFPGAN, upscaled again, and blended back through a soft oval mask.
- The models are converted from the original PyTorch weights to ONNX (`tools/export_onnx.py`). Weights are stored as fp16 and computed in fp32. The big models live on the [`models-v1` release](https://github.com/cryptoking12344-lgtm/Restro/releases/tag/models-v1).

## Build

Every push to `main` builds an APK with GitHub Actions and publishes it under **Releases** as `build-N`. If the `RESTRO_KEYSTORE_B64` and `RESTRO_KEY_PASS` secrets are set, every APK has the same signature, so each new build installs over the last one.

Model credits: Real-ESRGAN (Xintao Wang et al., BSD-3), GFPGAN (TencentARC, Apache-2.0).
