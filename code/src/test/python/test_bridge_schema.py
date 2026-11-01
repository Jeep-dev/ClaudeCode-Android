"""bridge.py's reading of Claude Code's settings schema out of its bundled (minified) program.

The parser is tested on a made-up line shaped like the real one; when a real `claude` is installed it
is also read, to find out early that a Claude Code release has changed what the parser relies on.

    python3 -I code/src/test/python/test_bridge_schema.py [path/to/bridge.py]
"""
import importlib.machinery
import importlib.util
import json
import os
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def load(path):
    os.environ['HOME'] = tempfile.mkdtemp(prefix='schematest')
    spec = importlib.util.spec_from_file_location('bridge', path, loader=importlib.machinery.SourceFileLoader('bridge', path))
    module = importlib.util.module_from_spec(spec)
    sys.modules['bridge'] = module
    spec.loader.exec_module(module)
    return module


def fixture():
    """A line as the bundle has them: lazy fields in a minified zod schema, the types being function names
    that differ per release (B: boolean, S: string, N: number, E: enum, O: object)."""
    fields = ['flag%d:()=>B().optional().describe("Flag %d")' % (i, i) for i in range(10)]
    fields += ['text%d:()=>S().optional().describe("Text %d")' % (i, i) for i in range(5)]
    fields += ['count%d:()=>N().int().optional().describe("Count %d")' % (i, i) for i in range(3)]
    fields += ['mode:()=>E(["fast","slow"]).optional().describe("Mode of work")',
               'blob:()=>O({}).optional().describe("Free form")',
               'secret:()=>B().optional().describe("@internal not for users")',
               'quoted:()=>S().optional().describe("Say \\"hi\\" \\u00e9" + " and more")',
               'dynamic:()=>S().optional().describe(`has ${x} in it`)',
               'later:()=>B().optional().catch(!1).describe("Caught flag")']
    helpers = 'u=S().url("x"),v=S().regex(/a[/]b/),w=S().email(),n=N().positive(),m=N().gte(1)'
    return 'var q={' + ','.join(fields) + '};' + helpers


def main():
    bridge = load(os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, '..', '..', 'main', 'assets', 'bridge.py')))
    failures = []

    def check(name, got, want):
        if got != want:
            failures.append('%s:\n   got  %r\n   want %r' % (name, got, want))

    found = {f['key']: f for f in bridge.parse_settings_schema(fixture())}
    check('flag', found['flag3'], {'key': 'flag3', 'desc': 'Flag 3', 'type': 'bool'})
    check('text', found['text2'], {'key': 'text2', 'desc': 'Text 2', 'type': 'string'})
    check('count', found['count1'], {'key': 'count1', 'desc': 'Count 1', 'type': 'number'})
    check('enum', found['mode'], {'key': 'mode', 'desc': 'Mode of work', 'type': 'enum', 'choices': ['fast', 'slow']})
    check('object', found['blob'], {'key': 'blob', 'desc': 'Free form', 'type': 'json'})
    check('escapes and +', found['quoted']['desc'], 'Say "hi" \u00e9 and more')
    check('internal left out', 'secret' in found, False)
    check('template with ${} left out', 'dynamic' in found, False)
    check('chained .catch', found['later']['type'], 'bool')
    check('count of fields', len(found), 22)
    check('too few fields', bridge.parse_settings_schema('a={x:()=>B().optional().describe("one")}'), [])
    check('no lazy fields', bridge.parse_settings_schema('nothing to see'), [])
    check('js_end', bridge.js_end('f(a,b),c', 0), 6)
    check('js_end in a string', bridge.js_end('"a,b",c', 0), 5)
    check('js_end regex with a bracket in it', bridge.js_end('f(/[)]/.test(x)),y', 0), 16)
    check('js_string', bridge.js_string('"a" + \'b\''), 'ab')
    check('js_string not a literal', bridge.js_string('x + "a"'), None)

    # The real one, when there is one: a release that changes the bundle shows up here first.
    real = bridge.shutil.which('claude')
    if real:
        began = time.time()
        schema = bridge.settings_schema()
        print('real %s: %d settings in %.1fs, types %s' % (real, len(schema), time.time() - began,
                                                           json.dumps({t: sum(1 for s in schema if s['type'] == t) for t in sorted({s['type'] for s in schema})})))
        if len(schema) < 100:
            failures.append('the installed Claude Code gave %d settings (over 100 expected): its bundle has probably changed' % len(schema))
        for key in ('permissions', 'env', 'model', 'hooks'):
            if key not in {s['key'] for s in schema}:
                failures.append('the installed Claude Code has no setting %s in what was read' % key)
    else:
        print('no claude installed: the real bundle is not read')

    for f in failures:
        print('FAIL', f)
    print('%d failures' % len(failures))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
