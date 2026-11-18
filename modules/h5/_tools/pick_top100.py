"""从 top100 目录 + 社区源(aoaostar)合并筛选浏览器可解析的纯规则文本书源"""
import json, glob, os, urllib.request

SRC_DIR = r'W:\Others\Incoming\PRJ\Reader3pic\top100'
KEEP = [
    'bookSourceName', 'bookSourceUrl', 'bookSourceGroup', 'searchUrl',
    'ruleSearch', 'ruleBookInfo', 'ruleToc', 'ruleContent',
    'header', 'enabled', 'bookSourceType', 'checkKeyWord',
]

def usable(x, group):
    s = json.dumps(x, ensure_ascii=False)
    if any(t in s for t in ['<js>', '@js:', 'java.', 'Packages.', 'rhino']):
        return None
    if x.get('bookSourceType', 0) != 0:
        return None
    if not (x.get('searchUrl') and x.get('ruleSearch') and x.get('ruleToc') and x.get('ruleContent')):
        return None
    item = {k: x[k] for k in KEEP if k in x}
    item['bookSourceGroup'] = group
    return item

all_items, rejected = [], 0
for f in glob.glob(os.path.join(SRC_DIR, '*.json')):
    group = 'top100_' + os.path.basename(f)[6:-5]
    raw = json.load(open(f, encoding='utf-8'))
    for x in raw:
        it = usable(x, group)
        if it: all_items.append(it)
        else: rejected += 1

# 社区源补充
try:
    community = json.load(open('community.json', encoding='utf-8'))
    for x in community:
        it = usable(x, '社区')
        if it: all_items.append(it)
except FileNotFoundError:
    print('no community.json, skip')

seen, out = set(), []
for x in all_items:
    if x['bookSourceUrl'] in seen: continue
    seen.add(x['bookSourceUrl'])
    out.append(x)

print('picked:', len(out), 'rejected:', rejected)
with open('sources.json', 'w', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
print('written', os.path.getsize('sources.json'), 'bytes')
