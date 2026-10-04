#!/usr/bin/env python3
"""Export the official Real-ESRGAN compact x4 model to ONNX.

Weights: realesr-general-x4v3.pth
Source: https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth
License: BSD-3-Clause (Copyright (c) 2021, Xintao Wang). See app/src/main/assets/models/NOTICE.

The network is the official SRVGGNetCompact used by realesr-general-x4v3
(num_feat=64, num_conv=32, upscale=4, PReLU), reimplemented here so the
weights can be loaded and exported. This file is a build tool; it is not
shipped inside the app.
"""

import argparse
import sys

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F


class SrvggNetCompact(nn.Module):
    """Compact VGG-style x4 network matching the official Real-ESRGAN definition."""

    def __init__(self, num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4):
        super().__init__()
        self.upscale = upscale
        body = [nn.Conv2d(num_in_ch, num_feat, 3, 1, 1), nn.PReLU(num_parameters=num_feat)]
        for _ in range(num_conv):
            body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            body.append(nn.PReLU(num_parameters=num_feat))
        body.append(nn.Conv2d(num_feat, num_out_ch * upscale * upscale, 3, 1, 1))
        self.body = nn.ModuleList(body)
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        base = F.interpolate(x, scale_factor=self.upscale, mode="nearest")
        return out + base


def load_official_weights(model, path):
    try:
        checkpoint = torch.load(path, map_location="cpu", weights_only=True)
    except TypeError:
        checkpoint = torch.load(path, map_location="cpu")
    if isinstance(checkpoint, dict) and "params_ema" in checkpoint:
        state = checkpoint["params_ema"]
    elif isinstance(checkpoint, dict) and "params" in checkpoint:
        state = checkpoint["params"]
    else:
        state = checkpoint
    model.load_state_dict(state, strict=True)


def export_onnx(model, destination):
    model.eval()
    dummy = torch.zeros(1, 3, 64, 64, dtype=torch.float32)
    export_kwargs = dict(
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={
            "input": {2: "height", 3: "width"},
            "output": {2: "height", 3: "width"},
        },
        opset_version=17,
        do_constant_folding=True,
    )
    try:
        torch.onnx.export(model, dummy, destination, dynamo=False, **export_kwargs)
    except TypeError:
        torch.onnx.export(model, dummy, destination, **export_kwargs)


def verify(model, onnx_path):
    model.eval()
    sample = torch.rand(1, 3, 48, 56, dtype=torch.float32)
    with torch.no_grad():
        expected = model(sample).numpy()
    session = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    actual = session.run(["output"], {"input": sample.numpy()})[0]
    if actual.shape != expected.shape:
        raise SystemExit(f"shape mismatch: onnx {actual.shape} pytorch {expected.shape}")
    max_abs = float(np.max(np.abs(actual - expected)))
    print(f"max abs diff vs pytorch: {max_abs:.6e}")
    if max_abs > 1e-4:
        raise SystemExit("ONNX output does not match PyTorch")
    if actual.shape[2] != sample.shape[2] * 4 or actual.shape[3] != sample.shape[3] * 4:
        raise SystemExit("expected a 4x spatial upscale")
    print("verified 4x ONNX export")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", required=True, help="Path to realesr-general-x4v3.pth")
    parser.add_argument("--output", required=True, help="Destination .onnx path")
    args = parser.parse_args()
    model = SrvggNetCompact()
    load_official_weights(model, args.weights)
    export_onnx(model, args.output)
    verify(model, args.output)
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
