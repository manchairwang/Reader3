const d = JSON.parse(require('fs').readFileSync(__dirname + '/sources.json', 'utf8'));
const x = d.find(s => s.bookSourceName === '精华书阁');
console.log(JSON.stringify({ url: x.bookSourceUrl, searchUrl: x.searchUrl, ruleToc: x.ruleToc, ruleContent: x.ruleContent }, null, 1));
