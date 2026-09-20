# Third-Party Notices

## Licensing scope

The original Music Filter source code is licensed under the Apache License,
Version 2.0, as stated in `LICENSE`. That licence does not relicense the
third-party components, source code, architecture, or model weights listed
below. Each third-party work remains subject to its own terms.

## Runtime libraries

| Component | Version used | Upstream licence |
| --- | --- | --- |
| AndroidX Core | 1.9.0 | Apache License 2.0 |
| AndroidX Media3 | 1.10.1 | Apache License 2.0 |
| ONNX Runtime for Android | 1.23.2 | MIT License |
| JTransforms | 3.1 | BSD 2-Clause License |

The complete dependency graph may include additional transitive Android and
Java components governed by their respective upstream licences.

## DTTNet source and architecture

The DTTNet source/architecture reference inspected during development was:

- Repository: `https://github.com/junyuchen-cjy/DTTNet-Pytorch.git`
- Inspected revision: `890a25ea4b8e0433d5e1fe5acf92a4eab781de61`
- Source-code licence declared by that repository: Apache License 2.0

This statement applies to the upstream source code only. It is not evidence
that pretrained checkpoints or converted model weights may be redistributed
under the same licence.

## Bundled DTTNet model weights

Licence: **NOT VERIFIED**

The repository includes the following DTTNet ONNX model weights:

- `dttnet_fp16_mixed.onnx` (production model)
- `dttnet_studentA_fp16.onnx`
- `dttnet_studentA_mixed.onnx`
- `dttnet_studentB_fp16.onnx`
- `dttnet_studentB_mixed.onnx`

No explicit checkpoint redistribution licence was found in the local records.
The Apache License 2.0 declaration for DTTNet source code must not be assumed
to cover these pretrained or converted weights. Confirm redistribution rights
with the applicable model/checkpoint owner before publishing this repository
or an APK that contains the weights.

## Production model provenance record

For `dttnet_fp16_mixed.onnx`:

- Original checkpoint filename: unknown
- Original checkpoint source and exact URL: unknown
- Local conversion: not established by the available records
- Conversion evidence: ONNX metadata identifies producer `pytorch`, version
  `2.14.0`; no conversion script or command is present in Git history
- SHA-256: `1414E65FE7AFF5C5316C337FE9C59468DF86350CDECF4D837BCB1318159C2C19`

The inspected upstream DTTNet README linked Mega and Baidu checkpoint folders,
but the local records do not establish that either folder was the source of
this particular ONNX file.
