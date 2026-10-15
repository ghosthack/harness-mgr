#!/usr/bin/env python3
from pathlib import Path, PurePosixPath
import sys, zipfile
jar, output, platform = sys.argv[1:]
subpath={'macos-arm64':'darwin/', 'windows-x64':'win/x86-64/', 'linux-x64':'linux/x86-64/'}[platform]
prefix='resources/com/pty4j/native/'+subpath
out=Path(output)/subpath;out.mkdir(parents=True,exist_ok=True);written=[]
with zipfile.ZipFile(jar) as source:
    for entry in source.infolist():
        if entry.is_dir() or not entry.filename.startswith(prefix):continue
        relative=PurePosixPath(entry.filename[len(prefix):])
        if len(relative.parts)!=1 or relative.name in ('.','..'):raise SystemExit('Unsafe native path')
        path=out/relative.name;path.write_bytes(source.read(entry));path.chmod(0o755);written.append(path.name)
if not written:raise SystemExit('No matching pty4j native resources')
print('Staged '+platform+' pty4j files: '+', '.join(sorted(written)))
