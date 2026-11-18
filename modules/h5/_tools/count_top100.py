import json, glob, os

total = 0
for f in glob.glob(r'W:\Others\Incoming\PRJ\Reader3pic\top100\*.json'):
    d = json.load(open(f, encoding='utf-8'))
    print(os.path.basename(f), len(d))
    total += len(d)
print('total', total)
