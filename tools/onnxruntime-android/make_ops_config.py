"""Writes ops.config, the operator list the app's ONNX Runtime is built with.

Usage: ORT_DISABLE_TELEMETRY=1 python3.12 make_ops_config.py <onnxruntime source checkout> <models dir>

<models dir> holds every .onnx the app runs — `find app/src/main/assets -name '*.onnx'` lists
them — and, for a model that is planned but not exported yet, a stand-in (the config of 2026-10-06
was made with a quantised Multilingual-MiniLM for the chat encoder). The header of ops.config names
the files it was made from. The config is the union of three sets:
  1. the operators of the raw graphs (create_reduced_build_config.py on the .onnx files);
  2. the operators of the same graphs after ORT's own level-"all" optimisation for ARM
     (convert_onnx_models_to_ort.py --optimization_style Fixed --target_platform arm), because the
     app creates its sessions with the default optimisation level and the fused com.microsoft
     kernels (FusedConv, SkipLayerNormalization, MatMulIntegerToFloat...) are what actually run —
     a config from the raw graphs alone fails on the phone with "Failed to find kernel";
  3. headroom: the same operator families at every opset from 11 to 18, so a model exported at a
     different opset still finds its kernel version.
Needs onnxruntime==<the version being built>, onnx and flatbuffers in the Python environment.
"""
import collections
import os
import subprocess
import sys
import tempfile

HEADROOM = """Add Sub Mul Div Pow Sqrt Exp Log Erf Tanh Sigmoid Relu HardSigmoid HardSwish Softmax LogSoftmax
MatMul Gemm MatMulInteger DynamicQuantizeLinear QuantizeLinear DequantizeLinear Gather GatherElements
Reshape Transpose Squeeze Unsqueeze Concat Slice Split Shape Size Cast Where Equal Less Greater Not And Or
Expand Tile Range ConstantOfShape ReduceMean ReduceSum ReduceMax ArgMax TopK Clip Min Max Neg Abs
Identity Constant CumSum Conv ConvTranspose BatchNormalization AveragePool MaxPool GlobalAveragePool
Resize Pad Flatten""".split()
CONTRIB = """Attention MultiHeadAttention SkipLayerNormalization SkipSimplifiedLayerNormalization
EmbedLayerNormalization BiasGelu FastGelu Gelu QuickGelu FusedConv FusedMatMul MatMulIntegerToFloat
DynamicQuantizeMatMul QAttention BiasSoftmax""".split()


def read(path, ops):
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#"):
            domain, opset, names = line.split(";")
            ops[(domain, int(opset))] |= {name for name in names.split(",") if name}


def header(models):
    """What ops.config opens with. The models are named from the folder itself: a sentence written
    here once went on describing a stand-in after the model it stood for had shipped."""
    names = sorted(
        os.path.relpath(os.path.join(folder, name), models)
        for folder, _, files in os.walk(models)
        for name in files
        if name.endswith(".onnx")
    )
    return (
        "# ONNX Runtime reduced-operator config for PWN.Health (see tools/onnxruntime-android/README.md)\n"
        f"# = {', '.join(names)},\n"
        '#   raw and ARM-optimised at level "all", plus the same op families at opsets 11-18 as headroom.\n'
    )


def main(src, models):
    ops = collections.defaultdict(set)
    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "raw.config")
        subprocess.run(
            [sys.executable, os.path.join(src, "tools/python/create_reduced_build_config.py"), "--format", "ONNX", models, raw],
            check=True,
        )
        read(raw, ops)
        subprocess.run(
            [sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort", models,
             "--optimization_style", "Fixed", "--target_platform", "arm", "--output_dir", tmp],
            check=True,
        )
        read(os.path.join(tmp, "required_operators.config"), ops)
    for opset in range(11, 19):
        ops[("ai.onnx", opset)] |= set(HEADROOM)
    ops[("ai.onnx", 17)].add("LayerNormalization")
    ops[("ai.onnx", 18)].add("LayerNormalization")
    ops[("com.microsoft", 1)] |= set(CONTRIB)
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "ops.config"), "w") as out:
        out.write(header(models))
        for domain, opset in sorted(ops):
            out.write(f"{domain};{opset};{','.join(sorted(ops[(domain, opset)]))}\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
