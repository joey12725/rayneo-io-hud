"""Synthetic reference only: Python 3.12, kaldi-native-fbank==1.23.1,
numpy==2.5.5, onnxruntime==1.32.0. No gateway access or enrolled voices.
Run: python android/tools/speaker_reference.py [--model PATH]
"""
import argparse
import pathlib
import numpy as np
import kaldi_native_fbank as knf

parser = argparse.ArgumentParser()
parser.add_argument('--model')
parser.add_argument('--output', type=pathlib.Path, help='Write fixtures to a temporary directory for live parity tests')
args = parser.parse_args()
root = args.output or pathlib.Path(__file__).resolve().parents[1] / 'app/src/test/resources/speaker'
root.mkdir(parents=True, exist_ok=True)
n = 25600
t = np.arange(n, dtype=np.float64) / 16000
seed = 7
noise = []
for _ in range(n):
    seed = (1664525 * seed + 1013904223) & 0xffffffff
    noise.append(((seed >> 16) - 32768) * .06)
signal = ((np.sin(2*np.pi*173*t) * 6000 + np.sin(2*np.pi*531*t) * 2500
           + np.sin(2*np.pi*1033*t) * 1800 + noise) * (.6 + .4*np.sin(2*np.pi*3*t)**2)).astype('<i2')
signal.tofile(root / 'synthetic.pcm')
opts = knf.FbankOptions()
opts.frame_opts.dither = 0
opts.frame_opts.samp_freq = 16000
opts.frame_opts.frame_length_ms = 25
opts.frame_opts.frame_shift_ms = 10
opts.frame_opts.window_type = 'hamming'
opts.mel_opts.num_bins = 80
opts.energy_floor = 0
fb = knf.OnlineFbank(opts)
fb.accept_waveform(16000, signal.astype(np.float32).tolist())
fb.input_finished()
features = np.stack([fb.get_frame(i) for i in range(fb.num_frames_ready)])
features = (features - features.mean(0, keepdims=True)).astype('<f4')
features.tofile(root / 'reference.f32')
print('Reference frames:', features.shape)
if args.model:
    import onnxruntime as ort
    options = ort.SessionOptions()
    session = ort.InferenceSession(args.model, sess_options=options, providers=['CPUExecutionProvider'])
    vec = session.run(None, {session.get_inputs()[0].name: features[None]})[0][0]
    (vec / np.linalg.norm(vec)).astype('<f4').tofile(root / 'embedding.f32')
    print('Reference embedding generated:', vec.shape)
