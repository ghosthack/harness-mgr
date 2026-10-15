#!/usr/bin/env python3
from pathlib import Path, PurePosixPath
import sys,zipfile,re,shutil
root=Path(sys.argv[1]);input_dir=Path(sys.argv[2]);bundle=input_dir/'THIRD-PARTY-LICENSES'
if bundle.exists():shutil.rmtree(bundle)
bundle.mkdir(parents=True);shutil.copyfile(root/'LICENSE',bundle/'APPLICATION-LICENSE');shutil.copyfile(root/'THIRD-PARTY.md',bundle/'THIRD-PARTY.md');shutil.copytree(root/'licenses',bundle/'dependencies/declared')
notice=root/'src/main/resources/io/github/ghosthack/harnessmanager/ui/provider-icons/NOTICE.md';shutil.copyfile(notice,bundle/'PROVIDER-ICON-NOTICE.md')
jars=list((root/'target/runtime-dependencies').glob('*.jar'))
if not jars:raise SystemExit('Runtime dependency inputs are missing')
legal=re.compile(r'(^|/)(LICENSE|LICENCE|NOTICE|COPYING|COPYRIGHT|PATENTS|BUILD-INFO|SOURCE-OFFER)(\.[^/]*)?$',re.I)
for jar in jars:
    with zipfile.ZipFile(jar) as source:
        for entry in source.infolist():
            if entry.is_dir() or not legal.search(entry.filename):continue
            rel=PurePosixPath(entry.filename)
            if rel.is_absolute() or '..' in rel.parts or '\\' in entry.filename:raise SystemExit('Unsafe legal path')
            dest=bundle/'dependencies/embedded'/jar.name/Path(*rel.parts);dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(source.read(entry))
for dependency in ['jediterm-core','jediterm-ui','jmux','harness-stim','flatlaf','pty4j','kotlin-stdlib','jna']:
    if not any(path.name.startswith(dependency+'-') for path in jars):raise SystemExit('Missing runtime dependency: '+dependency)
for required in ['PTY4J-LICENSE','PTY4J-NOTICE','JEDITERM-NOTICE.txt','LICENSE-APACHE-2.0.txt','jna-5.14.0-LICENSE']:
    if not (bundle/'dependencies/declared'/required).is_file():raise SystemExit('Missing controlling license: '+required)
(bundle/'DEPENDENCIES.txt').write_text('\n'.join(sorted(path.name for path in jars))+'\n')
(bundle/'CONTENTS.txt').write_text('\n'.join(sorted(str(p.relative_to(bundle)) for p in bundle.rglob('*') if p.is_file()))+'\n')
print('Staged runtime dependency notices and licenses')
