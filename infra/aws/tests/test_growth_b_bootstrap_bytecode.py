"""Exercise real Python imports through the shell entries without AWS or SQL."""
import hashlib
import json
import os
from pathlib import Path
import py_compile
import re
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
PROBE = '''import json, pathlib, subprocess, sys
modules = pathlib.Path(sys.argv[1])
sys.path.insert(0, str(modules))
import stale
child = subprocess.run([sys.executable, '-c',
    'import sys; sys.path.insert(0, sys.argv[1]); import child_only; '
    'print(int(sys.dont_write_bytecode))', str(modules)],
    check=True, capture_output=True, text=True)
print(json.dumps({'parentProtected': sys.dont_write_bytecode,
                  'childProtected': child.stdout.strip() == '1'}))
'''


class BootstrapBytecodeTests(unittest.TestCase):
    def inventory(self, root):
        return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in root.rglob('*') if path.is_file()}

    def execute(self, setup, command):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / 'toolchain/python/bin/python3'
            binary.parent.mkdir(parents=True)
            binary.symlink_to(sys.executable)
            modules = root / 'modules'; modules.mkdir()
            stale = modules / 'stale.py'; stale.write_text('value = 1\n')
            py_compile.compile(str(stale), doraise=True,
                               invalidation_mode=py_compile.PycInvalidationMode.TIMESTAMP)
            # The deterministic archive resets source mtimes, invalidating shipped pyc.
            os.utime(stale, (0, 0))
            (modules / 'child_only.py').write_text('value = 2\n')
            probe = root / 'probe.py'; probe.write_text(PROBE)
            before = self.inventory(modules)
            environment = {key: value for key, value in os.environ.items()
                           if not key.startswith('PYTHON')}
            # CI already exports this variable; the entry must establish it itself.
            shell = 'set -eu\nroot=$1\nstage=$root/stage\n' + setup + '\n' + command
            result = subprocess.run(['bash', '-c', shell, 'bytecode-test', str(root)],
                                    env=environment, text=True, capture_output=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(result.stdout), before, self.inventory(modules)

    def test_every_shell_entry_preserves_shipped_bytecode_and_child_inventory(self):
        counts = {'entry': 4, 'services': 4, 'snapshot': 2}
        for name, expected_count in counts.items():
            source = (SCRIPTS / ('bootstrap-growth-b-' + name + '.sh')).read_text()
            invocations = list(re.finditer(
                r'^[ \t]*(?:setsid )?("\$root/toolchain/python/bin/python3"|python3)'
                r'((?: -B)?) (?:(?:"\$(?:root|stage)/[^"\n]+")|"\$class_guard"|-(?=\s|$))', source, re.MULTILINE))
            self.assertEqual(len(invocations), expected_count, name)
            for number, invocation in enumerate(invocations):
                with self.subTest(entry=name, invocation=number):
                    executable, options = invocation.groups()
                    self.assertEqual(options, ' -B')
                    setup = '\n'.join(line for line in source[:invocation.start()].splitlines()
                                      if line.startswith(('export ', 'unset ')))
                    # Session creation is orthogonal; execute the real interpreter prefix.
                    command = executable + options + ' "$root/probe.py" "$root/modules"'
                    result, before, after = self.execute(setup, command)
                    self.assertEqual(result, {'parentProtected': True, 'childProtected': True})
                    self.assertEqual(before, after)

    def test_unprotected_imports_reproduce_overwritten_and_additional_bytecode(self):
        result, before, after = self.execute('',
            '"$root/toolchain/python/bin/python3" "$root/probe.py" "$root/modules"')
        self.assertEqual(result, {'parentProtected': False, 'childProtected': False})
        self.assertTrue(set(after) - set(before))
        self.assertTrue(any(before[name] != after[name] for name in before))


if __name__ == '__main__':
    unittest.main()
