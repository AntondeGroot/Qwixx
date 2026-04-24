import { Injectable } from '@angular/core';

type DiceColor = 'white' | 'red' | 'blue' | 'green' | 'yellow';
type DiceType  = 'anim' | 'results';

// Maps from red body colors → target body colors.
// Built by diffing the red SVG against each colored variant line-by-line.
const COLOR_MAPS: Record<'blue' | 'green' | 'yellow', Record<string, string>> = {
  blue: {
    // d6 anim
    'ffdce1': 'dcedff', 'e11034': '1079e1', 'ff4765': '47a3ff', 'a50621': '0656a5',
    'c83737': '3767c8', '810c0c': '0c3281', '1c0106': '010e1c', 'b60624': '065eb6',
    'bf4b44': '4466bf', 'c20627': '0665c2', 'c70e2d': '0e6bc7', '93051c': '054c93',
    '95061c': '064c95', 'eb5529': '293feb', 'f1425f': '4299f1',
    // d6 results (additions)
    'ee2345': '1d8cf2',
    '1d0006': '000f1d', 'ff7d92': '7dbeff', 'c80826': '0867c8', 'ff4f6c': '4fa6ff',
    // d8 anim (additions)
    'fbafaf': 'afd5fb', '830404': '044383', '1c0101': '010f1c', '830909': '094683',
    'd10909': '096dd1', 'bb0000': '005dbb', 'e64242': '4294e6', '520303': '032a52',
    'ffaeae': 'aed6ff', '860404': '044586', 'fcaeae': 'aed5fc', 'ffc6c6': 'c6e2ff',
    '610303': '033261', 'ff1818': '188bff', 'b60d0d': '0d61b6', '900909': '094d90',
    'c35b5b': '5b8fc3', '4f0303': '03294f', 'e90a0a': '0a79e9', 'f71212': '1284f7',
    'c45b5b': '5b8fc4',
    // d8 results (additions)
    'a30000': '0051a3', 'c70a0a': '0a68c7', 'b80808': '0860b8',
  },
  green: {
    // d6 anim
    'ffdce1': 'dcffdc', 'e11034': '11e110', 'ff4765': '47ff47', 'a50621': '06a506',
    'c83737': '37c84f', '810c0c': '0c811f', '1c0106': '011c02', 'b60624': '06b607',
    'bf4b44': '44bf5f', 'c20627': '07c206', 'c70e2d': '0fc70e', '93051c': '059305',
    '95061c': '069506', 'eb5529': '29eb75', 'f1425f': '42f142',
    // d6 results (additions)
    'ee2345': '1ef421',
    '1d0006': '011d00', 'ff7d92': '7dff7d', 'c80826': '08c809', 'ff4f6c': '4fff4f',
    // d8 anim (additions)
    'fbafaf': 'affbaf', '830404': '048304', '1c0101': '011c01', '830909': '098309',
    'd10909': '09d109', 'bb0000': '00bb00', 'e64242': '42e642', '520303': '035203',
    'ffaeae': 'aeffae', '860404': '048604', 'fcaeae': 'aefcae', 'ffc6c6': 'c6ffc6',
    '610303': '036103', 'ff1818': '18ff18', 'b60d0d': '0db60d', '900909': '099009',
    'c35b5b': '5bc35b', '4f0303': '034f03', 'e90a0a': '0ae90a', 'f71212': '12f712',
    'c45b5b': '5bc45b',
    // d8 results (additions)
    'a30000': '00a300', 'c70a0a': '0ac70a', 'b80808': '08b808',
  },
  yellow: {
    // d6 anim
    'ffdce1': 'fff3dc', 'e11034': 'e19b10', 'ff4765': 'ffc147', 'a50621': 'a56f06',
    'c83737': 'c8af37', '810c0c': '816d0c', '1c0106': '1c1301', 'b60624': 'b67b06',
    'bf4b44': 'bfb144', 'c20627': 'c28206', 'c70e2d': 'c7890e', '93051c': '936305',
    '95061c': '956606', 'eb5529': 'e0eb29', 'f1425f': 'f1b642',
    // d6 results (additions)
    'ee2345': 'eeae21',
    '1d0006': '1d1300', 'ff7d92': 'ffd37d', 'c80826': 'c88908', 'ff4f6c': 'ffc44f',
    // d8 anim (additions)
    'fbafaf': 'fbeeaf', '830404': '836d04', '1c0101': '1c1701', '830909': '836e09',
    'd10909': 'd1af09', 'bb0000': 'bb9b00', 'e64242': 'e6ca42', '520303': '524403',
    'ffaeae': 'fff1ae', '860404': '867004', 'fcaeae': 'fcefae', 'ffc6c6': 'fff5c6',
    '610303': '615103', 'ff1818': 'ffd818', 'b60d0d': 'b6990d', '900909': '907909',
    'c35b5b': 'c3b15b', '4f0303': '4f4203', 'e90a0a': 'e9c30a', 'f71212': 'f7d112',
    'c45b5b': 'c4b25b',
    // d8 results (additions)
    'a30000': 'a38700', 'c70a0a': 'c7a70a', 'b80808': 'b89a08',
  },
};

@Injectable({ providedIn: 'root' })
export class DiceSvgService {
  private readonly cache = new Map<string, Promise<string>>();

  getUrl(faces: 6 | 8, color: DiceColor, type: DiceType): Promise<string> {
    const key = `d${faces}_${color}_${type}`;
    if (!this.cache.has(key)) {
      this.cache.set(key, this.load(faces, color, type));
    }
    return this.cache.get(key)!;
  }

  private async load(faces: 6 | 8, color: DiceColor, type: DiceType): Promise<string> {
    const base = color === 'white' ? 'white' : 'red';
    let text = await fetch(`dice/d${faces}_${base}_${type}.svg`).then(r => r.text());

    if (color !== 'white' && color !== 'red') {
      const map = COLOR_MAPS[color];
      for (const [from, to] of Object.entries(map)) {
        text = text.replaceAll(`#${from}`, `#${to}`);
      }
    }

    return URL.createObjectURL(new Blob([text], { type: 'image/svg+xml' }));
  }
}