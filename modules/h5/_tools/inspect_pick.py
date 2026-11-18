import json, collections

d = json.load(open('sources.json', encoding='utf-8'))
print(collections.Counter(x.get('bookSourceGroup') for x in d))
print([x['bookSourceName'] for x in d[:15]])
