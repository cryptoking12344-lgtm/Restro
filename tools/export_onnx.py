"""Rebuilds Restro's ONNX models from the original PyTorch weights.

Network definitions are re-implemented here (no basicsr/gfpgan packages needed):
RRDBNet (Real-ESRGAN x4plus), SRVGGNetCompact (Real-ESRGAN general/anime v3) and GFPGANv1Clean.
Weights are stored as fp16 inside the ONNX files and cast to fp32 at load, halving the download.

usage: python tools/export_onnx.py WEIGHTS_DIR OUT_DIR [fast anime ultra face]
"""
import math, os, sys, numpy as np, torch, torch.nn as nn, torch.nn.functional as F, onnx
from onnx import helper, numpy_helper, TensorProto
import onnxruntime as ort

M, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)

class RDB(nn.Module):
    def __init__(s, nf=64, gc=32):
        super().__init__()
        s.conv1 = nn.Conv2d(nf, gc, 3, 1, 1); s.conv2 = nn.Conv2d(nf+gc, gc, 3, 1, 1)
        s.conv3 = nn.Conv2d(nf+2*gc, gc, 3, 1, 1); s.conv4 = nn.Conv2d(nf+3*gc, gc, 3, 1, 1)
        s.conv5 = nn.Conv2d(nf+4*gc, nf, 3, 1, 1); s.lrelu = nn.LeakyReLU(0.2, True)
    def forward(s, x):
        x1 = s.lrelu(s.conv1(x)); x2 = s.lrelu(s.conv2(torch.cat((x, x1), 1)))
        x3 = s.lrelu(s.conv3(torch.cat((x, x1, x2), 1))); x4 = s.lrelu(s.conv4(torch.cat((x, x1, x2, x3), 1)))
        return s.conv5(torch.cat((x, x1, x2, x3, x4), 1)) * 0.2 + x

class RRDB(nn.Module):
    def __init__(s, nf=64):
        super().__init__(); s.rdb1 = RDB(nf); s.rdb2 = RDB(nf); s.rdb3 = RDB(nf)
    def forward(s, x): return s.rdb3(s.rdb2(s.rdb1(x))) * 0.2 + x

class RRDBNet(nn.Module):
    def __init__(s, nf=64, nb=23):
        super().__init__()
        s.conv_first = nn.Conv2d(3, nf, 3, 1, 1); s.body = nn.Sequential(*[RRDB(nf) for _ in range(nb)])
        s.conv_body = nn.Conv2d(nf, nf, 3, 1, 1); s.conv_up1 = nn.Conv2d(nf, nf, 3, 1, 1)
        s.conv_up2 = nn.Conv2d(nf, nf, 3, 1, 1); s.conv_hr = nn.Conv2d(nf, nf, 3, 1, 1)
        s.conv_last = nn.Conv2d(nf, 3, 3, 1, 1); s.lrelu = nn.LeakyReLU(0.2, True)
    def forward(s, x):
        f = s.conv_first(x); f = f + s.conv_body(s.body(f))
        f = s.lrelu(s.conv_up1(F.interpolate(f, scale_factor=2, mode='nearest')))
        f = s.lrelu(s.conv_up2(F.interpolate(f, scale_factor=2, mode='nearest')))
        return s.conv_last(s.lrelu(s.conv_hr(f)))

def load_rrdb(p):
    net = RRDBNet(); sd = torch.load(p, map_location='cpu')
    net.load_state_dict(sd.get('params_ema', sd), strict=True); return net.eval()

class NormStyleCode(nn.Module):
    def forward(s, x): return x * torch.rsqrt(torch.mean(x ** 2, dim=1, keepdim=True) + 1e-8)

class ModulatedConv2d(nn.Module):
    def __init__(s, cin, cout, k, nsf, demodulate=True, sample_mode=None):
        super().__init__(); s.cout, s.k, s.dem, s.sm = cout, k, demodulate, sample_mode
        s.modulation = nn.Linear(nsf, cin, bias=True)
        s.weight = nn.Parameter(torch.zeros(1, cout, cin, k, k)); s.padding = k // 2
    def forward(s, x, style):
        b, c, h, w = x.shape
        st = s.modulation(style).view(b, 1, c, 1, 1); wt = s.weight * st
        if s.dem:
            wt = wt * torch.rsqrt(wt.pow(2).sum([2, 3, 4]) + 1e-8).view(b, s.cout, 1, 1, 1)
        wt = wt.view(b * s.cout, c, s.k, s.k)
        if s.sm == 'upsample': x = F.interpolate(x, scale_factor=2, mode='bilinear', align_corners=False)
        b, c, h, w = x.shape
        out = F.conv2d(x.view(1, b * c, h, w), wt, padding=s.padding, groups=b)
        return out.view(b, s.cout, *out.shape[2:4])

class StyleConv(nn.Module):
    def __init__(s, cin, cout, k, nsf, sample_mode=None):
        super().__init__(); s.modulated_conv = ModulatedConv2d(cin, cout, k, nsf, True, sample_mode)
        s.weight = nn.Parameter(torch.zeros(1)); s.bias = nn.Parameter(torch.zeros(1, cout, 1, 1))
    def forward(s, x, style, noise=None):
        out = s.modulated_conv(x, style) * 2 ** 0.5
        b, _, h, w = out.shape
        if noise is None: noise = out.new_empty(b, 1, h, w).normal_()
        return F.leaky_relu(out + s.weight * noise + s.bias, 0.2)

class ToRGB(nn.Module):
    def __init__(s, cin, nsf, upsample=True):
        super().__init__(); s.up = upsample
        s.modulated_conv = ModulatedConv2d(cin, 3, 1, nsf, False, None); s.bias = nn.Parameter(torch.zeros(1, 3, 1, 1))
    def forward(s, x, style, skip=None):
        out = s.modulated_conv(x, style) + s.bias
        if skip is not None:
            if s.up: skip = F.interpolate(skip, scale_factor=2, mode='bilinear', align_corners=False)
            out = out + skip
        return out

class Decoder(nn.Module):
    def __init__(s, out_size=512, nsf=512, cm=2):
        super().__init__()
        layers = [NormStyleCode()]
        for _ in range(8): layers += [nn.Linear(nsf, nsf), nn.LeakyReLU(0.2, True)]
        s.style_mlp = nn.Sequential(*layers)
        ch = {'4': 512, '8': 512, '16': 512, '32': 512, '64': 256 * cm, '128': 128 * cm, '256': 64 * cm, '512': 32 * cm}
        s.constant_input = nn.Module(); s.constant_input.weight = nn.Parameter(torch.zeros(1, ch['4'], 4, 4))
        s.style_conv1 = StyleConv(ch['4'], ch['4'], 3, nsf); s.to_rgb1 = ToRGB(ch['4'], nsf, False)
        s.log_size = int(math.log2(out_size)); s.num_layers = (s.log_size - 2) * 2 + 1
        s.style_convs, s.to_rgbs, s.noises = nn.ModuleList(), nn.ModuleList(), nn.Module()
        for i in range(s.num_layers):
            r = 2 ** ((i + 5) // 2); s.noises.register_buffer(f'noise{i}', torch.zeros(1, 1, r, r))
        cin = ch['4']
        for i in range(3, s.log_size + 1):
            co = ch[f'{2**i}']
            s.style_convs.append(StyleConv(cin, co, 3, nsf, 'upsample')); s.style_convs.append(StyleConv(co, co, 3, nsf))
            s.to_rgbs.append(ToRGB(co, nsf, True)); cin = co
    def forward(s, latent, conds):
        noise = [getattr(s.noises, f'noise{i}') for i in range(s.num_layers)]
        out = s.constant_input.weight.repeat(latent.shape[0], 1, 1, 1)
        out = s.style_conv1(out, latent[:, 0], noise[0]); skip = s.to_rgb1(out, latent[:, 1]); i = 1
        for c1, c2, n1, n2, trgb in zip(s.style_convs[::2], s.style_convs[1::2], noise[1::2], noise[2::2], s.to_rgbs):
            out = c1(out, latent[:, i], n1)
            if i < len(conds):
                a, b = torch.split(out, out.size(1) // 2, dim=1)
                out = torch.cat([a, b * conds[i - 1] + conds[i]], 1)
            out = c2(out, latent[:, i + 1], n2); skip = trgb(out, latent[:, i + 2], skip); i += 2
        return skip

class ResBlock(nn.Module):
    def __init__(s, cin, cout, mode):
        super().__init__(); s.conv1 = nn.Conv2d(cin, cin, 3, 1, 1); s.conv2 = nn.Conv2d(cin, cout, 3, 1, 1)
        s.skip = nn.Conv2d(cin, cout, 1, bias=False); s.sf = 0.5 if mode == 'down' else 2
    def forward(s, x):
        out = F.leaky_relu(s.conv1(x), 0.2)
        out = F.interpolate(out, scale_factor=s.sf, mode='bilinear', align_corners=False)
        out = F.leaky_relu(s.conv2(out), 0.2)
        return out + s.skip(F.interpolate(x, scale_factor=s.sf, mode='bilinear', align_corners=False))

class GFPGAN(nn.Module):
    def __init__(s, out_size=512, nsf=512, cm=2):
        super().__init__()
        ch = {'4': 256, '8': 256, '16': 256, '32': 256, '64': 256, '128': 128, '256': 64, '512': 32}
        s.log_size = int(math.log2(out_size)); s.nsf = nsf
        s.conv_body_first = nn.Conv2d(3, ch['512'], 1)
        s.conv_body_down = nn.ModuleList(); cin = ch['512']
        for i in range(s.log_size, 2, -1):
            co = ch[f'{2**(i-1)}']; s.conv_body_down.append(ResBlock(cin, co, 'down')); cin = co
        s.final_conv = nn.Conv2d(cin, ch['4'], 3, 1, 1)
        s.conv_body_up = nn.ModuleList(); cin = ch['4']
        for i in range(3, s.log_size + 1):
            co = ch[f'{2**i}']; s.conv_body_up.append(ResBlock(cin, co, 'up')); cin = co
        s.toRGB = nn.ModuleList([nn.Conv2d(ch[f'{2**i}'], 3, 1) for i in range(3, s.log_size + 1)])
        s.final_linear = nn.Linear(ch['4'] * 16, (s.log_size * 2 - 2) * nsf)
        s.stylegan_decoder = Decoder(out_size, nsf, cm)
        s.condition_scale, s.condition_shift = nn.ModuleList(), nn.ModuleList()
        for i in range(3, s.log_size + 1):
            co = ch[f'{2**i}']
            for m in (s.condition_scale, s.condition_shift):
                m.append(nn.Sequential(nn.Conv2d(co, co, 3, 1, 1), nn.LeakyReLU(0.2, True), nn.Conv2d(co, co, 3, 1, 1)))
    def forward(s, x):
        conds, skips = [], []
        feat = F.leaky_relu(s.conv_body_first(x), 0.2)
        for i in range(s.log_size - 2): feat = s.conv_body_down[i](feat); skips.insert(0, feat)
        feat = F.leaky_relu(s.final_conv(feat), 0.2)
        style = s.final_linear(feat.view(feat.size(0), -1)).view(feat.size(0), -1, s.nsf)
        for i in range(s.log_size - 2):
            feat = s.conv_body_up[i](feat + skips[i])
            conds.append(s.condition_scale[i](feat)); conds.append(s.condition_shift[i](feat))
        return s.stylegan_decoder(style, conds)


class SRVGG(nn.Module):
    def __init__(s, num_conv):
        super().__init__()
        layers = [nn.Conv2d(3, 64, 3, 1, 1), nn.PReLU(64)]
        for _ in range(num_conv): layers += [nn.Conv2d(64, 64, 3, 1, 1), nn.PReLU(64)]
        layers += [nn.Conv2d(64, 48, 3, 1, 1)]
        s.body = nn.ModuleList(layers); s.up = nn.PixelShuffle(4)
    def forward(s, x):
        out = x
        for l in s.body: out = l(out)
        return s.up(out) + F.interpolate(x, scale_factor=4, mode='nearest')

def srvgg(path, n):
    m = SRVGG(n); m.load_state_dict(torch.load(path, map_location='cpu')['params'], strict=True); return m.eval()

def blend(a, b, t):  # Real-ESRGAN "denoise strength": t*clean + (1-t)*weak-denoise weights
    m = SRVGG(32); sa = torch.load(a, map_location='cpu')['params']; sb = torch.load(b, map_location='cpu')['params']
    m.load_state_dict({k: t * sa[k] + (1 - t) * sb[k] for k in sa}); return m.eval()

def to_fp16_storage(path):
    model = onnx.load(path); g = model.graph; casts = []; keep = []
    for init in list(g.initializer):
        if init.data_type == TensorProto.FLOAT and np.prod(init.dims) >= 256:
            arr = numpy_helper.to_array(init).astype(np.float16)
            new = numpy_helper.from_array(arr, init.name + '_f16'); keep.append(new)
            casts.append(helper.make_node('Cast', [new.name], [init.name], to=TensorProto.FLOAT))
        else:
            keep.append(init)
    del g.initializer[:]; g.initializer.extend(keep)
    nodes = casts + list(g.node); del g.node[:]; g.node.extend(nodes)
    onnx.checker.check_model(model); onnx.save(model, path)

def export(model, name, shape, dynamic):
    x = torch.rand(*shape); p = f'{OUT}/{name}.onnx'
    kw = dict(input_names=['input'], output_names=['output'], opset_version=17, dynamo=False)
    if dynamic: kw['dynamic_axes'] = {'input': {2: 'h', 3: 'w'}, 'output': {2: 'H', 3: 'W'}}
    torch.onnx.export(model, x, p, **kw)
    to_fp16_storage(p)
    with torch.no_grad(): ref = model(x).numpy()
    sess = ort.InferenceSession(p, providers=['CPUExecutionProvider'])
    out = sess.run(None, {'input': x.numpy()})[0]
    print(name, os.path.getsize(p) // 1024, 'KB', 'max diff', float(np.abs(out - ref).max()), out.shape, flush=True)
    if dynamic:  # different tile size
        x2 = np.random.rand(1, 3, 40, 56).astype(np.float32); print('  dyn ok', sess.run(None, {'input': x2})[0].shape)

which = sys.argv[3:] or ['fast', 'anime', 'ultra', 'face']
with torch.no_grad():
    if 'fast' in which:
        a, b = f'{M}/realesr-general-x4v3.pth', f'{M}/realesr-general-wdn-x4v3.pth'
        export(blend(a, b, 1.0), 'fast_clean', (1, 3, 64, 64), True)
        export(blend(a, b, 0.5), 'fast_balanced', (1, 3, 64, 64), True)
        export(blend(a, b, 0.0), 'fast_grain', (1, 3, 64, 64), True)
    if 'anime' in which:
        export(srvgg(f'{M}/realesr-animevideov3.pth', 16), 'anime', (1, 3, 64, 64), True)
    if 'ultra' in which:
        export(load_rrdb(f'{M}/RealESRGAN_x4plus.pth'), 'ultra', (1, 3, 64, 64), True)
    if 'face' in which:
        net = GFPGAN(); net.load_state_dict(torch.load(f'{M}/GFPGANv1.4.pth', map_location='cpu')['params_ema']); net.eval()
        export(net, 'face_gfpgan14', (1, 3, 512, 512), False)
