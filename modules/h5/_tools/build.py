"""把 style.css + app.js + picgen.js + sources.json + picgen styles 合并成单一 index.html"""
import json, os, pathlib

root = pathlib.Path(__file__).resolve().parent.parent
css = (root / 'style.css').read_text(encoding='utf-8')
js = (root / 'app.js').read_text(encoding='utf-8')
picgen_js = (root / 'picgen.js').read_text(encoding='utf-8')
sources = json.loads((root / '_tools' / 'sources.json').read_text(encoding='utf-8'))
picgen_styles = json.loads((root / '_tools' / 'picgen_styles.json').read_text(encoding='utf-8'))

# 注入书源/生图风格
js = js.replace('/*__SOURCES__*/[]', json.dumps(sources, ensure_ascii=False, separators=(',', ':')))
picgen_js = picgen_js.replace('/*__PICGEN_STYLES__*/[]',
                              json.dumps(picgen_styles, ensure_ascii=False, separators=(',', ':')))

html = f"""<!DOCTYPE html>
<html lang="zh" data-theme="day">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>阅读 H5</title>
<style>
{css}
</style>
</head>
<body>
<div id="app"></div>
<script>
{picgen_js}
{js}
</script>
</body>
</html>
"""

out = root / 'index.html'
out.write_text(html, encoding='utf-8')
print('written', out, os.path.getsize(out), 'bytes,', len(sources), 'sources,', len(picgen_styles), 'styles')
