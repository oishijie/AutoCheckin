# -*- coding: utf-8 -*-
"""
在相机页截图里定位白色快门，输出中心坐标（屏幕像素 + 归一化比例）。

用途：验证「用图像识别找快门」这条路走不走得通，
      通了就在 App 里加一个 img/shutter 步骤作为坐标点击的兜底。

思路：
  1. 只在屏幕下半部分找（快门永远在底部工具条上，上半部是预览画面，
     对着白墙时会整片皆白，必须先排除掉）；
  2. 白色掩码后用 MinFilter 做「腐蚀」—— 快门是个实心大圆，
     「v」收起箭头、文字、细线都会被腐蚀掉，只剩快门那块；
  3. 对剩下的像素取质心，就是快门中心。
"""
import sys
import numpy as np
from PIL import Image, ImageFilter


def find_shutter(path, white_th=225, search_bottom=0.72, erode=17):
    img = Image.open(path).convert('RGB')
    w, h = img.size
    arr = np.array(img)

    y0 = int(h * search_bottom)
    roi = arr[y0:, :, :]

    mask = ((roi[:, :, 0] >= white_th) &
            (roi[:, :, 1] >= white_th) &
            (roi[:, :, 2] >= white_th)).astype(np.uint8) * 255
    raw_white = int((mask > 0).sum())

    mi = Image.fromarray(mask).filter(ImageFilter.MinFilter(erode))
    m = np.array(mi)
    ys, xs = np.nonzero(m)
    if len(xs) == 0:
        return {'ok': False, 'w': w, 'h': h, 'raw_white': raw_white}

    return {
        'ok': True,
        'w': w, 'h': h,
        'raw_white': raw_white,
        'blob_px': int(len(xs)),
        'cx': float(xs.mean()), 'cy': float(ys.mean()) + y0,
        'x1': int(xs.min()), 'x2': int(xs.max()),
        'y1': int(ys.min()) + y0, 'y2': int(ys.max()) + y0,
    }


def report(path):
    r = find_shutter(path)
    print('=' * 46)
    print('图片:', path)
    print('尺寸: %dx%d' % (r['w'], r['h']))
    print('下半屏白色像素: %d' % r['raw_white'])
    if not r['ok']:
        print('✗ 腐蚀后没有剩余 —— 没找到实心白色区域（快门）')
        return
    print('腐蚀后剩余: %d px' % r['blob_px'])
    print('块范围: x[%d,%d] y[%d,%d]  -> %dx%d'
          % (r['x1'], r['x2'], r['y1'], r['y2'],
             r['x2'] - r['x1'], r['y2'] - r['y1']))
    print('★ 快门中心 = (%.1f, %.1f)' % (r['cx'], r['cy']))
    print('  归一化   = (%.1f%%, %.1f%%)'
          % (r['cx'] / r['w'] * 100, r['cy'] / r['h'] * 100))


if __name__ == '__main__':
    args = sys.argv[1:]
    if not args:
        args = ['tmp-cam-page.png']
    for p in args:
        try:
            report(p)
        except Exception as e:
            print('处理 %s 出错: %s' % (p, e))
