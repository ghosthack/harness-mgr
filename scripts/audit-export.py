#!/usr/bin/env python3
"""Reject workstation data and private build configuration in a public source cut."""
from pathlib import Path
import re,sys,subprocess
root=Path(__file__).resolve().parents[1]
blocked=[re.compile(pattern) for pattern in [r'/Users/(tester|afgl12)(/|\b)', r'192[.]168[.]\d+[.]\d+', r'm[14]pro[-](li|2)\b', r'maven[.]pkg[.]github[.]com', r'BEGIN [A-Z ]*PRIVATE KEY', r'gh[pousr]_[A-Za-z0-9]{25,}', r'ghosthack@github[.]com', r'afgl12@faex[.]com', r'837911[+]ghosthack@users[.]noreply[.]github[.]com']]
email=re.compile(r'[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}')
allowed={'ghosthack@users.noreply.github.com','git@github.com'}
failures=[]
for path in root.rglob('*'):
    rel=path.relative_to(root)
    if any(part in {'.git','target','.oss-cache'} for part in rel.parts) or not path.is_file():continue
    if path.is_symlink():failures.append(str(rel)+' is a symlink');continue
    raw=path.read_bytes()
    if b'\0' in raw:continue
    text=raw.decode('utf-8',errors='ignore')
    if any(pattern.search(text) for pattern in blocked):failures.append(str(rel)+' contains private material')
    # Upstream license/copyright contacts must retain their original attribution.
    if rel.parts[0]!='licenses':
        for address in email.findall(text):
            if address not in allowed and not address.endswith(('@example.com','@example.org','@example.test')):
                failures.append(str(rel)+' contains an unapproved email address')
if failures:raise SystemExit('\n'.join(sorted(set(failures))))
if '--history' in sys.argv:
    expected='ghosthack <ghosthack@users.noreply.github.com> | ghosthack <ghosthack@users.noreply.github.com>'
    identities=subprocess.check_output(['git','-C',str(root),'log','main','--format=%an <%ae> | %cn <%ce>'],text=True).splitlines()
    if not identities or any(line!=expected for line in identities):raise SystemExit('Incorrect public commit identity')
print('Public source audit passed: private paths, registries, credentials and maintainer emails absent')
